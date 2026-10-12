package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.judge.QuizJudgeException;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeInterruptedException;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import kr.co.cking.quiz.judge.QuizJudgeTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Judge 전용 Executor thread에서 평가 Job 하나를 처리한다(이슈 #529).
 *
 * <ol>
 *   <li>PENDING → RUNNING 조건부 점유. 실패하면(이미 점유됐거나 끝난 Job) Provider를 호출하지 않고 끝낸다</li>
 *   <li>revision을 다시 검증하고 series에 고정한 입력 hash와 대조한다. 다르면 FAILED({@code INTEGRITY_FAILED})</li>
 *   <li>지금 실행 설정의 fingerprint를 series에 고정한 값과 비교한다. 다르면 Provider를 호출하지 않고
 *       FAILED({@code SETTINGS_MISMATCH}). series snapshot은 바꾸지 않는다</li>
 *   <li>Provider 호출 직전부터 {@link QuizJudge#executionTimeout()}만큼의 deadline을 잰다. 호출은 DB Transaction 밖이다</li>
 *   <li>결과를 받아도 deadline을 넘겼거나 thread가 중단됐으면 저장하지 않고 UNKNOWN이다. 아니면
 *       {@link QuizJudgeEvaluationStore#completeRunning}이 Job이 아직 RUNNING인지, revision·입력·설정·결과 hash와
 *       저장 계약을 다시 확인한 뒤 한 Transaction으로 저장한다. 잠금을 기다리는 동안 deadline을 넘기거나 중단되면
 *       Store가 저장 직전에 확인해 UNKNOWN으로 끝낸다</li>
 * </ol>
 * 시간 초과·중단은 결과를 확정할 수 없으므로 UNKNOWN, 그 밖의 실패는 FAILED다. 어느 경우에도 자동으로 다시 호출하지
 * 않으며, 관측한 실행 기록은 남긴다. 로그에는 Job ID·상태·실패 코드만 남긴다.
 */
@Slf4j
@Component
public class QuizJudgeWorker {

    private static final Set<QuizErrorCode> INTEGRITY_ERRORS = Set.of(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED,
            QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);

    private final QuizJudgeEvaluationStore store;
    private final QuizJudge judge;
    private final Clock clock;
    private final LongSupplier nanoTime;

    @Autowired
    public QuizJudgeWorker(QuizJudgeEvaluationStore store, QuizJudge judge, Clock clock) {
        this(store, judge, clock, System::nanoTime);
    }

    QuizJudgeWorker(QuizJudgeEvaluationStore store, QuizJudge judge, Clock clock, LongSupplier nanoTime) {
        this.store = store;
        this.judge = judge;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    public void run(Long jobId) {
        try {
            process(jobId);
        } catch (RuntimeException exception) {
            log.error("평가 Job 처리 중 예상하지 못한 오류가 났습니다. jobId={}, type={}", jobId,
                    exception.getClass().getSimpleName());
            finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTERNAL_ERROR, null);
        }
    }

    private void process(Long jobId) {
        if (!store.claim(jobId, clock.instant())) {
            log.info("이미 점유됐거나 끝난 평가 Job이라 실행하지 않습니다. jobId={}", jobId);
            return;
        }
        QuizJudgeEvaluationStore.JudgeRun run;
        try {
            run = store.loadInput(jobId);
        } catch (BusinessException exception) {
            if (!INTEGRITY_ERRORS.contains(exception.getErrorCode())) {
                throw exception;
            }
            finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTEGRITY_FAILED, null);
            return;
        }
        String currentSettings = QuizJudgeFingerprint.settingsFingerprint(
                judge.currentSettings(run.input().questions().size()));
        if (!currentSettings.equals(run.settingsFingerprint())) {
            finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.SETTINGS_MISMATCH, null);
            return;
        }
        QuizJudgeEvaluationStore.ExecutionDeadline deadline = new QuizJudgeEvaluationStore.ExecutionDeadline(
                nanoTime.getAsLong() + judge.executionTimeout().toNanos(), nanoTime);
        QuizJudgeResult result;
        try {
            result = judge.judge(run.input());
        } catch (QuizJudgeTimeoutException exception) {
            finish(jobId, QuizJudgeJobStatus.UNKNOWN, exception.isExecutionDeadlineExceeded()
                    ? QuizJudgeFailure.EXECUTION_TIMEOUT : QuizJudgeFailure.PROVIDER_TIMEOUT, exception.getExecution());
            return;
        } catch (QuizJudgeInterruptedException exception) {
            finish(jobId, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED, exception.getExecution());
            return;
        } catch (QuizJudgeException exception) {
            finish(jobId, QuizJudgeJobStatus.FAILED, failureOf(exception.getFailure()), exception.getExecution());
            return;
        }
        if (deadline.isExceeded()) {
            finish(jobId, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_TIMEOUT, result.execution());
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            finish(jobId, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED, result.execution());
            return;
        }
        complete(jobId, result, deadline);
    }

    /** 잠금 대기 중 deadline·중단은 Store가 첫 결과 INSERT 직전에 다시 확인하고 같은 Transaction에서 UNKNOWN으로 끝낸다. */
    private void complete(Long jobId, QuizJudgeResult result, QuizJudgeEvaluationStore.ExecutionDeadline deadline) {
        QuizJudgeEvaluationStore.Completion completion;
        try {
            completion = store.completeRunning(jobId, result, deadline);
        } catch (BusinessException exception) {
            if (!INTEGRITY_ERRORS.contains(exception.getErrorCode())) {
                throw exception;
            }
            finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTEGRITY_FAILED, result.execution());
            return;
        } catch (RuntimeException exception) {
            log.error("평가 결과를 저장하지 못했습니다. jobId={}, type={}", jobId, exception.getClass().getSimpleName());
            finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTERNAL_ERROR, result.execution());
            return;
        }
        switch (completion) {
            case SAVED -> log.info("평가 결과를 저장했습니다. jobId={}", jobId);
            case NOT_RUNNING -> log.info("이미 정리된 평가 Job이라 결과를 저장하지 않습니다. jobId={}", jobId);
            case IDENTITY_MISMATCH -> finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTEGRITY_FAILED,
                    result.execution());
            case INVALID_RESULT -> finish(jobId, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INVALID_RESPONSE,
                    result.execution());
            case DEADLINE_EXCEEDED, INTERRUPTED ->
                    log.warn("평가 결과를 저장하지 않고 UNKNOWN으로 끝냈습니다. jobId={}, completion={}", jobId, completion);
        }
    }

    private QuizJudgeFailure failureOf(QuizJudgeException.Failure failure) {
        return switch (failure) {
            case NOT_CONFIGURED -> QuizJudgeFailure.NOT_CONFIGURED;
            case REJECTED -> QuizJudgeFailure.PROVIDER_REJECTED;
            case PROVIDER_ERROR -> QuizJudgeFailure.PROVIDER_ERROR;
            case REFUSAL -> QuizJudgeFailure.PROVIDER_REFUSAL;
            case INCOMPLETE -> QuizJudgeFailure.INCOMPLETE_RESPONSE;
            case INVALID_RESPONSE -> QuizJudgeFailure.INVALID_RESPONSE;
        };
    }

    /**
     * RUNNING일 때만 끝 상태로 바꾸고 실행 기록을 남긴다. 중단된 thread에서는 DB 연결을 얻지 못할 수 있으므로
     * interrupt 상태를 잠시 비우고 기록한 뒤 되돌린다. 기록이 실패해도 Job은 stale 정리로 끝나므로 예외를 올리지 않는다.
     */
    private void finish(Long jobId, QuizJudgeJobStatus status, QuizJudgeFailure failure, QuizJudgeExecution execution) {
        boolean interrupted = Thread.interrupted();
        try {
            if (store.finishRunning(jobId, status, failure, execution, clock.instant())) {
                log.info("평가 Job을 끝 상태로 기록했습니다. jobId={}, status={}, failure={}", jobId, status, failure);
            } else {
                log.warn("평가 Job이 이미 RUNNING이 아니라 상태를 바꾸지 않았습니다. jobId={}, status={}, failure={}",
                        jobId, status, failure);
            }
        } catch (RuntimeException exception) {
            log.error("평가 Job 상태를 기록하지 못했습니다. jobId={}, type={}", jobId, exception.getClass().getSimpleName());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

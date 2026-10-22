package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.domain.QuizJudgeJobCall;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizJudgeSeries;
import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import kr.co.cking.quiz.domain.QuizJudgeStoredResultItem;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.judge.JudgeRubric;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator;
import kr.co.cking.quiz.judge.QuizJudgeException;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeInput;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import kr.co.cking.quiz.judge.QuizJudgeSettings;
import kr.co.cking.quiz.repository.QuizJudgeJobCallRepository;
import kr.co.cking.quiz.repository.QuizJudgeJobKeys;
import kr.co.cking.quiz.repository.QuizJudgeJobRepository;
import kr.co.cking.quiz.repository.QuizJudgeSeriesRepository;
import kr.co.cking.quiz.repository.QuizJudgeStoredResultItemRepository;
import kr.co.cking.quiz.repository.QuizJudgeStoredResultRepository;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionDecisionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Quiz Judge 평가의 짧은 DB Transaction 모음(이슈 #529). 메서드 하나가 Transaction 하나이며 Provider는 호출하지 않는다.
 *
 * <p>잠금 순서는 Quiz → 평가 묶음(series) → Job이다. 새 Job 접수와 결과 저장은 이 순서로 잡고, 상태만 바꾸는
 * UPDATE는 Job 행 하나만 잠근다. 같은 Quiz의 접수는 Quiz 잠금으로 직렬화되고, 다른 Quiz에 걸친 Creator의 진행 중 Job
 * 제한은 DB 고유 제약이 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizJudgeEvaluationStore {

    private final QuizRepository quizRepository;
    private final QuizRevisionRepository revisionRepository;
    private final QuizRevisionDecisionRepository decisionRepository;
    private final QuizRevisionReader revisionReader;
    private final QuizJudgeSeriesRepository seriesRepository;
    private final QuizJudgeJobRepository jobRepository;
    private final QuizJudgeJobCallRepository callRepository;
    private final QuizJudgeStoredResultRepository resultRepository;
    private final QuizJudgeStoredResultItemRepository itemRepository;
    private final QuizJudge judge;
    private final QuizJudgeEvaluationProperties properties;
    private final Clock clock;
    private final QuizJudgeEvaluationValidator validator = new QuizJudgeEvaluationValidator();

    /** HTTP 경로의 리소스 존재와 소유권을 먼저 확인한다. Provider 설정과는 무관하다. */
    @Transactional(readOnly = true)
    public void requireTarget(Long creatorId, Long quizId, Long revisionId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        if (!quiz.isOwnedBy(creatorId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        revisionRepository.findById(revisionId).filter(revision -> revision.getQuizId().equals(quizId))
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_REVISION_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Optional<QuizJudgeJob> findByRequest(Long creatorId, String requestId) {
        return jobRepository.findByCreatorIdAndRequestId(creatorId, requestId);
    }

    @Transactional(readOnly = true)
    public Optional<QuizJudgeJob> findJob(Long jobId) {
        return jobRepository.findById(jobId);
    }

    @Transactional(readOnly = true)
    public Optional<QuizJudgeStoredResult> findResult(Long jobId) {
        return resultRepository.findByJobId(jobId);
    }

    /** Creator의 오래된 PENDING은 FAILED, 오래된 RUNNING은 UNKNOWN으로 끝낸다. Provider를 다시 호출하지 않는다. */
    @Transactional
    public void expireStale(Long creatorId, Instant now) {
        jobRepository.expirePending(creatorId, now.minus(properties.getStalePending()), QuizJudgeJobStatus.PENDING,
                QuizJudgeJobStatus.FAILED, QuizJudgeFailure.STALE_PENDING, now);
        jobRepository.expireRunning(creatorId, now.minus(properties.getStaleRunning()), QuizJudgeJobStatus.RUNNING,
                QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.STALE_RUNNING, now);
    }

    /**
     * 평가 요청을 접수한다. Quiz → series 순서로 잠근 뒤 아래 순서로 확인하고, 시도 번호 할당과 Job 저장을 같은
     * Transaction에서 한다. Job 저장이 고유 제약에 걸리면 할당과 series 생성도 함께 롤백된다.
     * <ol>
     *   <li>없는 Quiz 404, 다른 Creator의 Quiz 403</li>
     *   <li>Quiz를 잠근 뒤 같은 requestId를 최신 커밋 기준으로 다시 찾는다. 처음 조회 뒤 다른 요청이 먼저 접수했으면
     *       대상이 같을 때 그 Job을 새로 만들지 않고 돌려주며({@link Admission#created()}가 false), 다르면 409다. 이
     *       확인은 아래 새 평가 조건보다 먼저 하므로 완료·한도 소진 뒤에도 같은 요청은 재요청으로 끝난다</li>
     *   <li>Quiz에 속하지 않은 revision 404, 승인된 revision은 다시 평가하지 않는다</li>
     *   <li>저장 내용이 revision 해시와 같아야 한다({@link QuizRevisionReader#readVerified})</li>
     *   <li>series가 없으면 지금 설정으로 만들고, 있으면 첫 요청 때 고정한 입력 hash와 같아야 한다</li>
     *   <li>결과가 저장된 series(fail·uncertain 포함)는 다시 평가하지 않는다</li>
     *   <li>Creator의 진행 중 Job은 하나다</li>
     *   <li>시도는 최초 포함 {@link QuizJudgeSeries#MAX_ATTEMPTS}번이다. 앞선 시도는 FAILED·UNKNOWN이어야 한다</li>
     *   <li>지금 실행 설정의 fingerprint가 series에 고정한 값과 같아야 한다. 다르면 결과를 저장할 수 없으므로 시도를
     *       할당하지 않고 409다. series snapshot과 시도 횟수는 그대로 둔다</li>
     * </ol>
     * 접수 시점의 설정 확인일 뿐이다. 실행 전에 설정이 바뀔 수 있으므로 Worker는 Provider 호출 직전에 다시 확인해야
     * 한다({@link #loadInput}).
     */
    @Transactional
    public Admission create(Long creatorId, Long memberId, Long quizId, Long revisionId, String requestId,
                            Instant now) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        if (!quiz.isOwnedBy(creatorId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        // 이 Transaction의 첫 일반 읽기다. InnoDB REPEATABLE READ는 첫 일반 읽기 때 읽기 시점을 정하고 위의 잠금 읽기는
        // 시점을 정하지 않으므로, Quiz 잠금을 기다리는 동안 커밋된 같은 requestId 접수가 보인다. 이 앞에 일반 읽기를
        // 두지 않는다.
        Optional<QuizJudgeJob> existing = jobRepository.findByCreatorIdAndRequestId(creatorId, requestId);
        if (existing.isPresent()) {
            if (!existing.get().targets(quizId, revisionId)) {
                throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
            }
            return new Admission(existing.get(), false);
        }
        quiz.requireNotClosed();
        QuizRevision revision = revisionRepository.findById(revisionId)
                .filter(found -> found.getQuizId().equals(quizId))
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_REVISION_NOT_FOUND));
        if (decisionRepository.findByRevisionId(revisionId).filter(QuizRevisionDecision::isApproved).isPresent()) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_REVISION_APPROVED);
        }
        String inputSha256 = QuizJudgeFingerprint.inputSha256(judgeInput(quiz, revision));
        QuizJudgeSettings currentSettings = judge.currentSettings(revision.getQuestionCount());
        QuizJudgeSeries series = seriesRepository.findByRevisionIdForUpdate(revisionId)
                .orElseGet(() -> seriesRepository.saveAndFlush(new QuizJudgeSeries(quizId, revisionId,
                        quiz.getContentTextSha256(), revision.getRevisionSha256(), inputSha256, currentSettings, now)));
        requireSameRevision(series, quiz, revision, inputSha256);
        if (resultRepository.existsBySeriesId(series.getSeriesId())) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_COMPLETED);
        }
        if (jobRepository.existsByCreatorIdAndStatusIn(creatorId, QuizJudgeJobStatus.ACTIVE)) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS);
        }
        if (!series.hasAttemptLeft()) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED);
        }
        if (!series.getSettingsFingerprint().equals(QuizJudgeFingerprint.settingsFingerprint(currentSettings))) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_SETTINGS_MISMATCH);
        }
        int attemptNo = series.allocateAttempt(now);
        return new Admission(
                jobRepository.saveAndFlush(new QuizJudgeJob(series, creatorId, memberId, requestId, attemptNo, now)),
                true);
    }

    /**
     * 접수 결과.
     *
     * @param created true면 이번 호출이 새 Job을 만들었다. false면 같은 requestId로 이미 접수된 Job이며, 호출자는 이를
     *                재요청으로 다뤄 실행을 다시 제출하지 않는다
     */
    public record Admission(QuizJudgeJob job, boolean created) {
    }

    /**
     * Worker용. Job의 revision을 다시 검증해 Judge 입력을 만들고, series에 고정한 설정 fingerprint와 함께 돌려준다.
     * 저장 내용이나 고정한 입력 hash와 다르면 500이다.
     *
     * <p>Worker는 이 입력으로 Provider를 호출하기 직전에 {@code QuizJudge#currentSettings}의 fingerprint가
     * {@link JudgeRun#settingsFingerprint()}와 같은지 다시 확인해야 한다. 접수 뒤 설정이 바뀌면 그 결과는
     * {@link #completeRunning}에서 {@link Completion#IDENTITY_MISMATCH}로 저장되지 않으므로, 호출하지 않고 끝내야
     * 비용이 들지 않는다.
     */
    @Transactional(readOnly = true)
    public JudgeRun loadInput(Long jobId) {
        QuizJudgeJobKeys keys = requireKeys(jobId);
        Quiz quiz = quizRepository.findById(keys.quizId()).orElseThrow(() -> missing("Quiz", jobId));
        QuizRevision revision = revisionRepository.findById(keys.revisionId())
                .orElseThrow(() -> missing("revision", jobId));
        QuizJudgeSeries series = seriesRepository.findById(keys.seriesId()).orElseThrow(() -> missing("series", jobId));
        QuizJudgeInput input = judgeInput(quiz, revision);
        requireSameRevision(series, quiz, revision, QuizJudgeFingerprint.inputSha256(input));
        return new JudgeRun(input, series.getSettingsFingerprint());
    }

    /**
     * Worker가 실행할 평가 한 번.
     *
     * @param settingsFingerprint series에 고정한 설정 fingerprint. 바꾸지 않는다
     */
    public record JudgeRun(QuizJudgeInput input, String settingsFingerprint) {
    }

    /** Worker용. PENDING → RUNNING. 이미 다른 상태면 false다. */
    @Transactional
    public boolean claim(Long jobId, Instant now) {
        return jobRepository.claim(jobId, QuizJudgeJobStatus.PENDING, QuizJudgeJobStatus.RUNNING, now) == 1;
    }

    /** Worker용. 제출이 거절되면 PENDING일 때만 FAILED로 끝낸다. */
    @Transactional
    public boolean failPending(Long jobId, QuizJudgeFailure failure, Instant now) {
        return jobRepository.failPending(jobId, QuizJudgeJobStatus.PENDING, QuizJudgeJobStatus.FAILED, failure, now)
                == 1;
    }

    /**
     * Worker용. RUNNING일 때만 FAILED·UNKNOWN으로 끝내고 실행 기록을 남긴다. 이미 정리된 Job은 상태를 바꾸지 않고, 실행
     * 기록이 아직 없으면 비용이 사라지지 않게 기록만 남긴다.
     *
     * @param execution 실행 기록. Provider를 호출하기 전에 끝났으면 null
     * @return 상태를 바꿨으면 true
     */
    @Transactional
    public boolean finishRunning(Long jobId, QuizJudgeJobStatus status, QuizJudgeFailure failure,
                                 QuizJudgeExecution execution, Instant now) {
        QuizJudgeJob job = jobRepository.findByIdForUpdate(jobId).orElseThrow(() -> missing("Job", jobId));
        if (job.getStatus() != QuizJudgeJobStatus.RUNNING) {
            recordExecutionIfAbsent(job, execution);
            return false;
        }
        job.finish(status, failure, now);
        recordExecutionIfAbsent(job, execution);
        return true;
    }

    /**
     * Worker용. Quiz → series → Job 순서로 잠근 뒤, Job이 아직 RUNNING이고 결과가 revision·series에 고정한 입력과
     * 설정에 맞을 때만 불변 결과와 문항 × rubric 판정을 저장하고 SUCCEEDED로 바꾼다. 결과·판정·실행 기록·상태 변경은
     * 한 Transaction이라 함께 커밋되거나 함께 롤백된다.
     *
     * <p>잠금 대기 중 Worker의 실행 deadline을 넘기거나 thread가 중단될 수 있으므로, 첫 결과 INSERT 직전에 다시 확인한다.
     * 넘겼으면 결과를 저장하지 않고 같은 Transaction에서 UNKNOWN({@code EXECUTION_TIMEOUT}·
     * {@code EXECUTION_INTERRUPTED})으로 끝내며 실행 기록은 남긴다. interrupt 상태는 그대로 둔다. 완료 시각은 잠금을
     * 얻은 뒤의 시각이다.
     */
    @Transactional
    public Completion completeRunning(Long jobId, QuizJudgeResult result, ExecutionDeadline deadline) {
        QuizJudgeJobKeys keys = requireKeys(jobId);
        Quiz quiz = quizRepository.findByIdForUpdate(keys.quizId()).orElseThrow(() -> missing("Quiz", jobId));
        QuizJudgeSeries series = seriesRepository.findByIdForUpdate(keys.seriesId())
                .orElseThrow(() -> missing("series", jobId));
        QuizJudgeJob job = jobRepository.findByIdForUpdate(jobId).orElseThrow(() -> missing("Job", jobId));
        if (job.getStatus() != QuizJudgeJobStatus.RUNNING) {
            recordExecutionIfAbsent(job, result.execution());
            return Completion.NOT_RUNNING;
        }
        QuizRevision revision = revisionRepository.findById(keys.revisionId())
                .orElseThrow(() -> missing("revision", jobId));
        requireSameRevision(series, quiz, revision, QuizJudgeFingerprint.inputSha256(judgeInput(quiz, revision)));
        QuizJudgeEvaluation evaluation;
        try {
            // Adapter가 이미 검증했어도 저장 경계에서 revision 문항 수와 series에 고정한 reason 한도로 다시 확인한다.
            evaluation = validator.validate(result.evaluation().questions(), revision.getQuestionCount(),
                    series.getReasonMaxLength());
        } catch (QuizJudgeException exception) {
            log.error("평가 결과가 저장 계약을 지키지 않습니다. jobId={}, seriesId={}", jobId, series.getSeriesId());
            return Completion.INVALID_RESULT;
        }
        if (!matchesSeries(series, result, evaluation)) {
            log.error("평가 결과가 고정한 입력·설정과 다릅니다. jobId={}, seriesId={}", jobId, series.getSeriesId());
            return Completion.IDENTITY_MISMATCH;
        }
        Instant now = clock.instant();
        if (deadline.isExceeded()) {
            log.warn("잠금을 기다리는 동안 실행 시간을 넘겨 평가 결과를 저장하지 않습니다. jobId={}", jobId);
            job.finish(QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_TIMEOUT, now);
            recordExecutionIfAbsent(job, result.execution());
            return Completion.DEADLINE_EXCEEDED;
        }
        if (Thread.currentThread().isInterrupted()) {
            log.warn("실행이 중단돼 평가 결과를 저장하지 않습니다. jobId={}", jobId);
            job.finish(QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED, now);
            recordExecutionIfAbsent(job, result.execution());
            return Completion.INTERRUPTED;
        }
        QuizJudgeStoredResult stored = resultRepository.saveAndFlush(new QuizJudgeStoredResult(job, result, now));
        List<QuizJudgeStoredResultItem> items = new ArrayList<>();
        for (QuizJudgeEvaluation.QuestionEvaluation question : evaluation.questions()) {
            for (JudgeRubric rubric : JudgeRubric.values()) {
                QuizJudgeEvaluation.RubricResult item = question.item(rubric);
                items.add(new QuizJudgeStoredResultItem(stored.getResultId(), question.questionNo(), rubric,
                        item.verdict(), item.reason()));
            }
        }
        itemRepository.saveAll(items);
        job.succeed(now);
        recordExecutionIfAbsent(job, result.execution());
        return Completion.SAVED;
    }

    /**
     * 결과 저장 결과. SAVED가 아니면 결과와 판정은 저장하지 않았다(실행 기록만 남길 수 있다).
     * INVALID_RESULT·IDENTITY_MISMATCH는 Job 상태를 바꾸지 않으므로 Worker가 FAILED로 끝낸다. 두 값은 저장할 수 없는
     * 결과라는 점에서 같게 다룬다. DEADLINE_EXCEEDED·INTERRUPTED는 이 Transaction에서 이미 UNKNOWN으로 끝냈다.
     */
    public enum Completion {
        SAVED,
        /** stale 정리 등으로 이미 끝난 Job이다. 늦게 끝난 결과는 버린다. */
        NOT_RUNNING,
        /** 문항 번호(0부터 revision 문항 수-1까지 한 번씩)·rubric 항목·reason 규칙을 지키지 않는다. */
        INVALID_RESULT,
        /** 결과의 입력 hash·설정 fingerprint·결과 hash가 series나 내용과 맞지 않는다. */
        IDENTITY_MISMATCH,
        /** 잠금을 얻은 뒤 실행 deadline을 넘긴 것을 확인해 UNKNOWN({@code EXECUTION_TIMEOUT})으로 끝냈다. */
        DEADLINE_EXCEEDED,
        /** 잠금을 얻은 뒤 thread 중단을 확인해 UNKNOWN({@code EXECUTION_INTERRUPTED})으로 끝냈다. */
        INTERRUPTED
    }

    /**
     * Worker가 Provider 호출 직전부터 잰 실행 deadline. 같은 monotonic 시계로 비교한다.
     *
     * @param deadlineNanos {@code nanoTime} 기준 deadline
     */
    public record ExecutionDeadline(long deadlineNanos, LongSupplier nanoTime) {

        public boolean isExceeded() {
            return nanoTime.getAsLong() > deadlineNanos;
        }
    }

    /**
     * 결과가 series에 고정한 입력·설정으로 만든 것이고, 결과 hash가 저장 경계에서 검증한 내용으로 다시 계산한 값과
     * 같은지 확인한다.
     */
    private boolean matchesSeries(QuizJudgeSeries series, QuizJudgeResult result, QuizJudgeEvaluation evaluation) {
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(result.settings());
        String resultSha256 = QuizJudgeFingerprint.resultSha256(result.inputSha256(), result.settingsFingerprint(),
                result.settings().policyVersion(), evaluation);
        return series.getInputSha256().equals(result.inputSha256())
                && series.getSettingsFingerprint().equals(settingsFingerprint)
                && settingsFingerprint.equals(result.settingsFingerprint())
                && resultSha256.equals(result.resultSha256());
    }

    private void recordExecutionIfAbsent(QuizJudgeJob job, QuizJudgeExecution execution) {
        if (execution == null || job.getHttpRequestCount() != null) {
            return;
        }
        job.recordExecution(execution);
        List<QuizJudgeJobCall> calls = new ArrayList<>();
        List<QuizJudgeExecution.Exchange> exchanges = execution.exchanges();
        for (int index = 0; index < exchanges.size(); index++) {
            calls.add(new QuizJudgeJobCall(job.getJobId(), index + 1, exchanges.get(index)));
        }
        callRepository.saveAll(calls);
    }

    private void requireSameRevision(QuizJudgeSeries series, Quiz quiz, QuizRevision revision, String inputSha256) {
        if (!series.getInputSha256().equals(inputSha256)
                || !series.getContentTextSha256().equals(quiz.getContentTextSha256())
                || !series.getRevisionSha256().equals(revision.getRevisionSha256())) {
            log.error("평가 묶음이 고정한 revision 내용과 다릅니다. seriesId={}, revisionId={}",
                    series.getSeriesId(), revision.getRevisionId());
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        }
    }

    /** 저장 내용을 revision 해시로 검증한 뒤 Judge 입력으로 바꾼다. 보기 순서와 정답 번호는 저장된 그대로다. */
    private QuizJudgeInput judgeInput(Quiz quiz, QuizRevision revision) {
        List<QuizJudgeInput.Question> questions = new ArrayList<>();
        for (QuizQuestionContent content : revisionReader.readVerified(quiz, revision)) {
            questions.add(new QuizJudgeInput.Question(content.questionNo(), content.question(), content.options(),
                    content.correctOptionIndex(), content.explanation(), content.sourceEvidence()));
        }
        return new QuizJudgeInput(quiz.getContentText(), questions);
    }

    private QuizJudgeJobKeys requireKeys(Long jobId) {
        return jobRepository.findKeys(jobId).orElseThrow(() -> missing("Job", jobId));
    }

    private IllegalStateException missing(String target, Long jobId) {
        return new IllegalStateException("평가 " + target + "이(가) 없습니다. jobId=" + jobId);
    }
}

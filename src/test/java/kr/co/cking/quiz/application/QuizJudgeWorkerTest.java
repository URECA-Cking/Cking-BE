package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.judge.JudgeUsage;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator;
import kr.co.cking.quiz.judge.QuizJudgeException;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeFixtures;
import kr.co.cking.quiz.judge.QuizJudgeInput;
import kr.co.cking.quiz.judge.QuizJudgeInterruptedException;
import kr.co.cking.quiz.judge.QuizJudgeResponseParser;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import kr.co.cking.quiz.judge.QuizJudgeSettings;
import kr.co.cking.quiz.judge.QuizJudgeTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class QuizJudgeWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");
    private static final long JOB_ID = 1L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuizJudgeEvaluationStore store = mock(QuizJudgeEvaluationStore.class);
    private final QuizJudge judge = mock(QuizJudge.class);
    private final AtomicLong nanos = new AtomicLong();
    private final QuizJudgeWorker worker = new QuizJudgeWorker(store, judge, Clock.fixed(NOW, ZoneOffset.UTC),
            nanos::get);
    private final QuizJudgeInput input = QuizJudgeFixtures.input(3);
    private final QuizJudgeSettings settings = QuizJudgeSettings.current("model-a", "medium", 8192, 200, objectMapper);
    private final QuizJudgeExecution execution = new QuizJudgeExecution("model-a", List.of(
            new QuizJudgeExecution.Exchange(429, null, null, null),
            new QuizJudgeExecution.Exchange(200, "resp_1", "model-a-2026", new JudgeUsage(1000L, null, 500L, 300L,
                    1500L))),
            new QuizJudgeExecution.CostEstimate(null, "pricing-v1", false));

    @BeforeEach
    void setUp() {
        given(store.claim(JOB_ID, NOW)).willReturn(true);
        given(store.loadInput(JOB_ID)).willReturn(new QuizJudgeEvaluationStore.JudgeRun(input,
                QuizJudgeFingerprint.settingsFingerprint(settings)));
        given(judge.currentSettings(3)).willReturn(settings);
        given(judge.executionTimeout()).willReturn(Duration.ofSeconds(150));
        given(store.completeRunning(eq(JOB_ID), eq(result(QuizJudgeFixtures.allPass(3))), any()))
                .willReturn(QuizJudgeEvaluationStore.Completion.SAVED);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 점유한_Job만_검증_설정_확인_호출_저장_순서로_처리한다() {
        QuizJudgeResult result = result(QuizJudgeFixtures.allPass(3));
        given(judge.judge(input)).willReturn(result);
        given(store.completeRunning(eq(JOB_ID), eq(result), any())).willReturn(QuizJudgeEvaluationStore.Completion.SAVED);

        worker.run(JOB_ID);

        InOrder order = inOrder(store, judge);
        order.verify(store).claim(JOB_ID, NOW);
        order.verify(store).loadInput(JOB_ID);
        order.verify(judge).currentSettings(3);
        order.verify(judge).judge(input);
        order.verify(store).completeRunning(eq(JOB_ID), eq(result), any());
        verify(store, never()).finishRunning(anyLong(), any(), any(), any(), any());
    }

    @Test
    void fail_uncertain_판정도_정상_결과로_저장을_맡긴다() {
        QuizJudgeResult result = result(QuizJudgeFixtures.output(3, (no, rubric) -> no == 0
                ? new String[] {"fail", "근거"} : new String[] {"uncertain", "근거"}));
        given(judge.judge(input)).willReturn(result);
        given(store.completeRunning(eq(JOB_ID), eq(result), any())).willReturn(QuizJudgeEvaluationStore.Completion.SAVED);

        worker.run(JOB_ID);

        verify(store).completeRunning(eq(JOB_ID), eq(result), any());
        verify(store, never()).finishRunning(anyLong(), any(), any(), any(), any());
    }

    @Test
    void 점유하지_못하면_Provider를_호출하지_않는다() {
        given(store.claim(JOB_ID, NOW)).willReturn(false);

        worker.run(JOB_ID);

        verify(store, never()).loadInput(anyLong());
        verify(judge, never()).judge(any());
        verify(store, never()).finishRunning(anyLong(), any(), any(), any(), any());
    }

    @Test
    void 끝_상태를_기록하면_실패_경고가_아니라_기록_로그를_남긴다(CapturedOutput output) {
        given(judge.currentSettings(3)).willReturn(QuizJudgeSettings.current("model-b", "medium", 8192, 200,
                objectMapper));
        given(store.finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.SETTINGS_MISMATCH, null, NOW))
                .willReturn(true);

        worker.run(JOB_ID);

        assertThat(output.getAll()).contains("평가 Job을 끝 상태로 기록했습니다.", "status=FAILED",
                        "failure=SETTINGS_MISMATCH")
                .doesNotContain("끝나지 못했습니다", "상태를 바꾸지 않았습니다");
    }

    @Test
    void 이미_끝난_Job이라_상태를_바꾸지_못하면_경고한다(CapturedOutput output) {
        given(judge.currentSettings(3)).willReturn(QuizJudgeSettings.current("model-b", "medium", 8192, 200,
                objectMapper));
        given(store.finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.SETTINGS_MISMATCH, null, NOW))
                .willReturn(false);

        worker.run(JOB_ID);

        assertThat(output.getAll()).contains("평가 Job이 이미 RUNNING이 아니라 상태를 바꾸지 않았습니다.")
                .doesNotContain("평가 Job을 끝 상태로 기록했습니다.");
    }

    @Test
    void 실행_시점_설정이_series와_다르면_Provider를_호출하지_않고_FAILED다() {
        given(judge.currentSettings(3)).willReturn(QuizJudgeSettings.current("model-b", "medium", 8192, 200,
                objectMapper));

        worker.run(JOB_ID);

        verify(judge, never()).judge(any());
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.SETTINGS_MISMATCH, null, NOW);
    }

    @Test
    void revision이나_입력_hash가_어긋나면_Provider를_호출하지_않고_FAILED다() {
        for (QuizErrorCode code : new QuizErrorCode[] {QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED,
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED}) {
            willThrow(new BusinessException(code)).given(store).loadInput(JOB_ID);

            worker.run(JOB_ID);
        }

        verify(judge, never()).judge(any());
        verify(store, times(2)).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED,
                QuizJudgeFailure.INTEGRITY_FAILED, null, NOW);
    }

    @Test
    void 시간_초과는_UNKNOWN이고_응답_대기와_전체_실행_초과를_구분하며_실행_기록을_남긴다() {
        willThrow(new QuizJudgeTimeoutException(false, execution)).given(judge).judge(input);
        worker.run(JOB_ID);
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.PROVIDER_TIMEOUT, execution,
                NOW);

        willThrow(new QuizJudgeTimeoutException(true, execution)).given(judge).judge(input);
        worker.run(JOB_ID);
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_TIMEOUT, execution,
                NOW);
        verify(store, never()).completeRunning(anyLong(), any(), any());
    }

    @Test
    void 중단은_UNKNOWN이고_기록할_때만_interrupt를_비운_뒤_되돌린다() {
        willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new QuizJudgeInterruptedException(QuizJudgeInterruptedException.Phase.HTTP_REQUEST, execution);
        }).given(judge).judge(input);
        AtomicBoolean interruptedWhileRecording = new AtomicBoolean(true);
        willAnswer(invocation -> {
            interruptedWhileRecording.set(Thread.currentThread().isInterrupted());
            return true;
        }).given(store).finishRunning(JOB_ID, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED,
                execution, NOW);

        worker.run(JOB_ID);

        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED,
                execution, NOW);
        assertThat(interruptedWhileRecording).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 결과를_받은_뒤_중단됐으면_저장하지_않고_UNKNOWN이다() {
        QuizJudgeResult result = result(QuizJudgeFixtures.allPass(3));
        willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return result;
        }).given(judge).judge(input);

        worker.run(JOB_ID);

        verify(store, never()).completeRunning(anyLong(), any(), any());
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED,
                result.execution(), NOW);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 확정된_Provider_실패는_사유별_FAILED이고_자동으로_다시_호출하지_않는다() {
        QuizJudgeException.Failure[] failures = QuizJudgeException.Failure.values();
        QuizJudgeFailure[] expected = {QuizJudgeFailure.NOT_CONFIGURED, QuizJudgeFailure.PROVIDER_REJECTED,
                QuizJudgeFailure.PROVIDER_ERROR, QuizJudgeFailure.PROVIDER_REFUSAL,
                QuizJudgeFailure.INCOMPLETE_RESPONSE, QuizJudgeFailure.INVALID_RESPONSE};
        assertThat(failures).hasSameSizeAs(expected);
        for (int index = 0; index < failures.length; index++) {
            willThrow(new QuizJudgeException(failures[index], execution)).given(judge).judge(input);

            worker.run(JOB_ID);

            verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, expected[index], execution, NOW);
        }
        verify(judge, times(failures.length)).judge(input);
        verify(store, never()).completeRunning(anyLong(), any(), any());
    }

    @Test
    void 결과를_받았어도_전체_실행_시간을_넘겼으면_저장하지_않고_UNKNOWN이다() {
        QuizJudgeResult result = result(QuizJudgeFixtures.allPass(3));
        willAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(151).toNanos());
            return result;
        }).given(judge).judge(input);

        worker.run(JOB_ID);

        verify(store, never()).completeRunning(anyLong(), any(), any());
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_TIMEOUT,
                result.execution(), NOW);
    }

    @Test
    void 저장할_수_없는_결과는_RUNNING으로_두지_않고_FAILED이며_늦은_결과는_그대로_둔다() {
        QuizJudgeResult result = result(QuizJudgeFixtures.allPass(3));
        given(judge.judge(input)).willReturn(result);

        given(store.completeRunning(eq(JOB_ID), eq(result), any())).willReturn(QuizJudgeEvaluationStore.Completion.IDENTITY_MISMATCH);
        worker.run(JOB_ID);
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTEGRITY_FAILED,
                result.execution(), NOW);

        given(store.completeRunning(eq(JOB_ID), eq(result), any())).willReturn(QuizJudgeEvaluationStore.Completion.INVALID_RESULT);
        worker.run(JOB_ID);
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INVALID_RESPONSE,
                result.execution(), NOW);

        given(store.completeRunning(eq(JOB_ID), eq(result), any())).willReturn(QuizJudgeEvaluationStore.Completion.NOT_RUNNING);
        worker.run(JOB_ID);
        verify(store, times(2)).finishRunning(anyLong(), any(), any(), any(), any());
    }

    @Test
    void 저장_중_무결성_실패나_예상하지_못한_오류도_실행_기록과_함께_FAILED로_끝낸다() {
        QuizJudgeResult result = result(QuizJudgeFixtures.allPass(3));
        given(judge.judge(input)).willReturn(result);

        willThrow(new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED)).given(store)
                .completeRunning(eq(JOB_ID), eq(result), any());
        worker.run(JOB_ID);
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTEGRITY_FAILED,
                result.execution(), NOW);

        willThrow(new IllegalStateException("db")).given(store).completeRunning(eq(JOB_ID), eq(result), any());
        worker.run(JOB_ID);
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTERNAL_ERROR,
                result.execution(), NOW);
    }

    @Test
    void 호출_전_예상하지_못한_오류는_FAILED이고_기록_실패도_밖으로_던지지_않는다() {
        willThrow(new IllegalStateException("missing")).given(store).loadInput(JOB_ID);
        willThrow(new IllegalStateException("db down")).given(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED,
                QuizJudgeFailure.INTERNAL_ERROR, null, NOW);

        worker.run(JOB_ID);

        verify(judge, never()).judge(any());
        verify(store).finishRunning(JOB_ID, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.INTERNAL_ERROR, null, NOW);
    }

    @Test
    void Store가_deadline_초과로_끝냈으면_상태를_다시_기록하거나_Provider를_재호출하지_않는다() {
        assertStoreTerminated(QuizJudgeEvaluationStore.Completion.DEADLINE_EXCEEDED);
    }

    @Test
    void Store가_interrupt로_끝냈으면_상태를_다시_기록하거나_Provider를_재호출하지_않는다() {
        assertStoreTerminated(QuizJudgeEvaluationStore.Completion.INTERRUPTED);
    }

    private void assertStoreTerminated(QuizJudgeEvaluationStore.Completion completion) {
        QuizJudgeResult result = result(QuizJudgeFixtures.allPass(3));
        given(judge.judge(input)).willReturn(result);
        given(store.completeRunning(eq(JOB_ID), eq(result), any())).willAnswer(invocation -> {
            QuizJudgeEvaluationStore.ExecutionDeadline deadline = invocation.getArgument(2);
            assertThat(deadline.deadlineNanos()).isEqualTo(Duration.ofSeconds(150).toNanos());
            nanos.set(Duration.ofSeconds(151).toNanos());
            assertThat(deadline.isExceeded()).isTrue();
            return completion;
        });

        worker.run(JOB_ID);

        verify(judge, times(1)).judge(input);
        verify(store, times(1)).completeRunning(eq(JOB_ID), eq(result), any());
        verify(store, never()).finishRunning(anyLong(), any(), any(), any(), any());
    }

    /** 입력·설정으로 hash를 맞춘 결과. Provider 원문은 담지 않는다. */
    private QuizJudgeResult result(String outputJson) {
        QuizJudgeEvaluation evaluation = new QuizJudgeEvaluationValidator().validate(
                new QuizJudgeResponseParser(objectMapper).parse(outputJson), 3, 200);
        String inputSha256 = QuizJudgeFingerprint.inputSha256(input);
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(settings);
        return new QuizJudgeResult(evaluation, settings, inputSha256, settingsFingerprint,
                QuizJudgeFingerprint.resultSha256(inputSha256, settingsFingerprint, settings.policyVersion(), evaluation),
                new QuizJudgeExecution("model-a", List.of(new QuizJudgeExecution.Exchange(200, "resp_1", "model-a-2026",
                        new JudgeUsage(1000L, 200L, 500L, 300L, 1500L))),
                        new QuizJudgeExecution.CostEstimate(new BigDecimal("0.0067"), "pricing-v1", true)));
    }
}

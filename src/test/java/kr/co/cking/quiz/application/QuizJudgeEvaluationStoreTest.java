package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.quiz.QuizFixtures;
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
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.JudgeRubric;
import kr.co.cking.quiz.judge.JudgeUsage;
import kr.co.cking.quiz.judge.JudgeVerdict;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeFixtures;
import kr.co.cking.quiz.judge.QuizJudgeInput;
import kr.co.cking.quiz.judge.QuizJudgeResponseParser;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QuizJudgeEvaluationStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final long CREATOR_ID = 10L;
    private static final long MEMBER_ID = 7L;
    private static final long QUIZ_ID = 50L;
    private static final long REVISION_ID = 500L;
    private static final long SERIES_ID = 30L;
    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuizRevisionRepository revisionRepository = mock(QuizRevisionRepository.class);
    private final QuizRevisionDecisionRepository decisionRepository = mock(QuizRevisionDecisionRepository.class);
    private final QuizRevisionReader revisionReader = mock(QuizRevisionReader.class);
    private final QuizJudgeSeriesRepository seriesRepository = mock(QuizJudgeSeriesRepository.class);
    private final QuizJudgeJobRepository jobRepository = mock(QuizJudgeJobRepository.class);
    private final QuizJudgeJobCallRepository callRepository = mock(QuizJudgeJobCallRepository.class);
    private final QuizJudgeStoredResultRepository resultRepository = mock(QuizJudgeStoredResultRepository.class);
    private final QuizJudgeStoredResultItemRepository itemRepository = mock(QuizJudgeStoredResultItemRepository.class);
    private final QuizJudge judge = mock(QuizJudge.class);
    private final QuizJudgeEvaluationProperties properties = new QuizJudgeEvaluationProperties();
    private final QuizJudgeEvaluationStore store = new QuizJudgeEvaluationStore(quizRepository, revisionRepository,
            decisionRepository, revisionReader, seriesRepository, jobRepository, callRepository, resultRepository,
            itemRepository, judge, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    /** 지나지 않는 deadline. */
    private static final QuizJudgeEvaluationStore.ExecutionDeadline OPEN =
            new QuizJudgeEvaluationStore.ExecutionDeadline(Long.MAX_VALUE, () -> 0L);

    private final Quiz quiz = QuizFixtures.quiz(QUIZ_ID, CREATOR_ID);
    private final QuizRevision revision = revision();
    private final QuizJudgeSettings settings = QuizJudgeSettings.current("model-a", "medium", 8192, 200, objectMapper);

    @BeforeEach
    void setUp() {
        given(quizRepository.findByIdForUpdate(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(revisionRepository.findById(REVISION_ID)).willReturn(Optional.of(revision));
        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.empty());
        given(revisionReader.readVerified(quiz, revision)).willReturn(contents());
        given(judge.currentSettings(3)).willReturn(settings);
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.empty());
        given(seriesRepository.saveAndFlush(any(QuizJudgeSeries.class))).willAnswer(invocation -> {
            QuizJudgeSeries series = invocation.getArgument(0);
            ReflectionTestUtils.setField(series, "seriesId", SERIES_ID);
            return series;
        });
        given(jobRepository.saveAndFlush(any(QuizJudgeJob.class))).willAnswer(invocation -> {
            QuizJudgeJob job = invocation.getArgument(0);
            ReflectionTestUtils.setField(job, "jobId", 1L);
            return job;
        });
    }

    @Test
    void 첫_요청은_Quiz와_series를_잠근_순서로_series를_만들고_첫_시도를_같은_Transaction에서_할당한다() {
        QuizJudgeJob job = create();

        InOrder order = inOrder(quizRepository, revisionReader, seriesRepository, jobRepository);
        order.verify(quizRepository).findByIdForUpdate(QUIZ_ID);
        order.verify(revisionReader).readVerified(quiz, revision);
        order.verify(seriesRepository).findByRevisionIdForUpdate(REVISION_ID);
        order.verify(seriesRepository).saveAndFlush(any(QuizJudgeSeries.class));
        order.verify(jobRepository).saveAndFlush(any(QuizJudgeJob.class));
        assertThat(job.getAttemptNo()).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.PENDING);
        assertThat(job.getSeriesId()).isEqualTo(SERIES_ID);
        assertThat(job.targets(QUIZ_ID, REVISION_ID)).isTrue();

        ArgumentCaptor<QuizJudgeSeries> series = ArgumentCaptor.forClass(QuizJudgeSeries.class);
        verify(seriesRepository).saveAndFlush(series.capture());
        assertThat(series.getValue().getAllocatedAttempts()).isEqualTo(1);
        assertThat(series.getValue().getInputSha256()).isEqualTo(QuizJudgeFingerprint.inputSha256(input()));
        assertThat(series.getValue().getSettingsFingerprint()).isEqualTo(QuizJudgeFingerprint.settingsFingerprint(settings));
        assertThat(series.getValue().settings()).isEqualTo(settings);
        assertThat(series.getValue().getContentTextSha256()).isEqualTo(quiz.getContentTextSha256());
        assertThat(series.getValue().getRevisionSha256()).isEqualTo(revision.getRevisionSha256());
    }

    @Test
    void FAILED_UNKNOWN_뒤에는_같은_series에서_두_번째_시도를_할당하고_세_번째는_막는다() {
        QuizJudgeSeries series = series(1, settings);
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.of(series));

        assertThat(create().getAttemptNo()).isEqualTo(2);
        assertThat(series.getAllocatedAttempts()).isEqualTo(2);
        verify(seriesRepository, never()).saveAndFlush(any());

        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED);
        assertThat(series.getAllocatedAttempts()).isEqualTo(2);
    }

    @Test
    void 첫_시도_실패_뒤_설정이_바뀌면_두_번째_시도를_할당하지_않고_series를_그대로_둔다() {
        QuizJudgeSeries series = series(1, settings);
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.of(series));
        given(judge.currentSettings(3)).willReturn(QuizJudgeSettings.current("model-b", "medium", 4096, 150, objectMapper));

        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_SETTINGS_MISMATCH);

        assertThat(series.getAllocatedAttempts()).isEqualTo(1);
        assertThat(series.getSettingsFingerprint()).isEqualTo(QuizJudgeFingerprint.settingsFingerprint(settings));
        assertThat(series.getModel()).isEqualTo("model-a");
        verify(seriesRepository, never()).saveAndFlush(any());
        verify(jobRepository, never()).saveAndFlush(any());
        verify(judge, never()).judge(any());
    }

    @Test
    void Quiz를_잠근_뒤_같은_requestId가_보이면_새_평가_조건을_보지_않고_그_Job을_재요청으로_돌려준다() {
        QuizJudgeJob existing = runningJob();
        given(jobRepository.findByCreatorIdAndRequestId(CREATOR_ID, REQUEST_ID)).willReturn(Optional.of(existing));
        // 완료·한도 소진·진행 중·설정 변경 상태여도 재요청이 먼저다.
        given(resultRepository.existsBySeriesId(SERIES_ID)).willReturn(true);
        given(jobRepository.existsByCreatorIdAndStatusIn(CREATOR_ID, QuizJudgeJobStatus.ACTIVE)).willReturn(true);
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.of(series(2, settings)));
        given(judge.currentSettings(3)).willReturn(QuizJudgeSettings.current("model-b", "medium", 4096, 150, objectMapper));

        QuizJudgeEvaluationStore.Admission admission = admit();

        assertThat(admission.created()).isFalse();
        assertThat(admission.job()).isSameAs(existing);
        InOrder order = inOrder(quizRepository, jobRepository);
        order.verify(quizRepository).findByIdForUpdate(QUIZ_ID);
        order.verify(jobRepository).findByCreatorIdAndRequestId(CREATOR_ID, REQUEST_ID);
        verify(revisionReader, never()).readVerified(any(), any());
        verify(seriesRepository, never()).findByRevisionIdForUpdate(any());
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void Quiz를_잠근_뒤_보인_같은_requestId가_다른_대상이면_409다() {
        QuizJudgeJob other = runningJob();
        ReflectionTestUtils.setField(other, "revisionId", 501L);
        given(jobRepository.findByCreatorIdAndRequestId(CREATOR_ID, REQUEST_ID)).willReturn(Optional.of(other));

        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
        verify(seriesRepository, never()).findByRevisionIdForUpdate(any());
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void 새로_접수하면_created가_true다() {
        assertThat(admit().created()).isTrue();
    }

    @Test
    void 결과가_저장된_series는_fail_uncertain이어도_다시_평가하지_않는다() {
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.of(series(1, settings)));
        given(resultRepository.existsBySeriesId(SERIES_ID)).willReturn(true);

        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_EVALUATION_COMPLETED);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void 승인된_revision은_무결성_검증과_series_생성_전에_막는다() {
        given(decisionRepository.findByRevisionId(REVISION_ID))
                .willReturn(Optional.of(QuizFixtures.approval(REVISION_ID, MEMBER_ID)));

        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_REVISION_APPROVED);
        verify(revisionReader, never()).readVerified(any(), any());
        verify(seriesRepository, never()).findByRevisionIdForUpdate(any());
    }

    @Test
    void 거절된_revision은_평가를_막지_않는다() {
        given(decisionRepository.findByRevisionId(REVISION_ID))
                .willReturn(Optional.of(QuizFixtures.rejection(REVISION_ID, MEMBER_ID)));

        assertThat(create().getAttemptNo()).isEqualTo(1);
    }

    @Test
    void Creator에게_진행_중_Job이_있으면_시도를_할당하지_않고_409다() {
        QuizJudgeSeries series = series(1, settings);
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.of(series));
        given(jobRepository.existsByCreatorIdAndStatusIn(CREATOR_ID, QuizJudgeJobStatus.ACTIVE)).willReturn(true);

        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS);
        assertThat(series.getAllocatedAttempts()).isEqualTo(1);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void revision_무결성이_깨졌으면_series나_Job을_만들지_않는다() {
        willThrow(new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED))
                .given(revisionReader).readVerified(quiz, revision);
        assertError(this::create, QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        verify(seriesRepository, never()).findByRevisionIdForUpdate(any());

        willReturn(contents()).given(revisionReader).readVerified(quiz, revision);
        QuizJudgeSeries changed = series(1, settings);
        ReflectionTestUtils.setField(changed, "inputSha256", "0".repeat(64));
        given(seriesRepository.findByRevisionIdForUpdate(REVISION_ID)).willReturn(Optional.of(changed));
        assertError(this::create, QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void 없는_Quiz_다른_Creator의_Quiz_다른_Quiz의_revision을_구분한다() {
        given(quizRepository.findByIdForUpdate(QUIZ_ID)).willReturn(Optional.empty());
        assertError(this::create, QuizErrorCode.QUIZ_NOT_FOUND);

        given(quizRepository.findByIdForUpdate(QUIZ_ID)).willReturn(Optional.of(QuizFixtures.quiz(QUIZ_ID, 99L)));
        assertError(this::create, CommonErrorCode.FORBIDDEN);

        given(quizRepository.findByIdForUpdate(QUIZ_ID)).willReturn(Optional.of(quiz));
        QuizRevision other = new QuizRevision(51L, 1, "제목", "v1", 3, 4, "r".repeat(64), NOW);
        ReflectionTestUtils.setField(other, "revisionId", REVISION_ID);
        given(revisionRepository.findById(REVISION_ID)).willReturn(Optional.of(other));
        assertError(this::create, QuizErrorCode.QUIZ_REVISION_NOT_FOUND);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void Job_저장이_고유_제약에_걸리면_예외를_그대로_올려_할당과_series_생성을_함께_롤백하게_한다() {
        willThrow(new DataIntegrityViolationException("duplicate")).given(jobRepository).saveAndFlush(any());

        assertThatThrownBy(this::create).isInstanceOf(DataIntegrityViolationException.class);
        verify(seriesRepository).saveAndFlush(any(QuizJudgeSeries.class));
    }

    @Test
    void 오래된_PENDING은_15분_RUNNING은_5분_기준으로_정리한다() {
        given(jobRepository.findPendingToExpire(CREATOR_ID, NOW.minus(Duration.ofMinutes(15)), QuizJudgeJobStatus.PENDING))
                .willReturn(List.of(101L));
        given(jobRepository.findRunningToExpire(CREATOR_ID, NOW.minus(Duration.ofMinutes(5)), QuizJudgeJobStatus.RUNNING))
                .willReturn(List.of(102L));
        store.expireStale(CREATOR_ID, NOW);

        verify(jobRepository).expirePending(101L, NOW.minus(Duration.ofMinutes(15)), QuizJudgeJobStatus.PENDING,
                QuizJudgeJobStatus.FAILED, QuizJudgeFailure.STALE_PENDING, NOW);
        verify(jobRepository).expireRunning(102L, NOW.minus(Duration.ofMinutes(5)), QuizJudgeJobStatus.RUNNING,
                QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.STALE_RUNNING, NOW);
    }

    @Test
    void 만료후보가_없으면_범위_UPDATE를_실행하지_않는다() {
        store.expireStale(CREATOR_ID, NOW);
        verify(jobRepository, never()).expirePending(any(), any(), any(), any(), any(), any());
        verify(jobRepository, never()).expireRunning(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 결과_저장은_Quiz_series_Job_순서로_잠그고_결과와_판정과_실행_기록을_함께_저장한다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        given(resultRepository.saveAndFlush(any(QuizJudgeStoredResult.class))).willAnswer(invocation -> {
            QuizJudgeStoredResult stored = invocation.getArgument(0);
            ReflectionTestUtils.setField(stored, "resultId", 801L);
            return stored;
        });
        QuizJudgeResult result = result(settings);

        assertThat(store.completeRunning(1L, result, OPEN)).isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);

        InOrder order = inOrder(quizRepository, seriesRepository, jobRepository, resultRepository);
        order.verify(quizRepository).findByIdForUpdate(QUIZ_ID);
        order.verify(seriesRepository).findByIdForUpdate(SERIES_ID);
        order.verify(jobRepository).findByIdForUpdate(1L);
        order.verify(resultRepository).saveAndFlush(any(QuizJudgeStoredResult.class));
        ArgumentCaptor<QuizJudgeStoredResult> stored = ArgumentCaptor.forClass(QuizJudgeStoredResult.class);
        verify(resultRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getJobId()).isEqualTo(1L);
        assertThat(stored.getValue().getSeriesId()).isEqualTo(SERIES_ID);
        assertThat(stored.getValue().getRevisionId()).isEqualTo(REVISION_ID);
        assertThat(stored.getValue().getResultSha256()).isEqualTo(result.resultSha256());
        assertThat(stored.getValue().getAggregation()).isEqualTo(JudgeAggregation.ALL_PASS);
        assertThat(stored.getValue().getPolicyVersion()).isEqualTo("quiz-judge-policy-v1");
        assertThat(savedItems()).hasSize(21)
                .allSatisfy(item -> assertThat(item.getResultId()).isEqualTo(801L));
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
        assertThat(job.getCompletedAt()).isEqualTo(NOW);
        assertThat(job.getHttpRequestCount()).isEqualTo(2);
        assertThat(job.getRequestedModel()).isEqualTo("model-a");
        assertThat(job.getResponseModel()).isEqualTo("model-a-2026");
        assertThat(job.getUsageComplete()).isFalse();
        assertThat(job.getEstimatedCostUsd()).isEqualByComparingTo("0.0067");
        assertThat(job.getPricingVersion()).isEqualTo("pricing-v1");
        assertThat(job.getCostComplete()).isFalse();
        List<QuizJudgeJobCall> calls = savedCalls();
        assertThat(calls).extracting(QuizJudgeJobCall::getCallNo).containsExactly(1, 2);
        assertThat(calls.get(0).getHttpStatus()).isEqualTo(429);
        assertThat(calls.get(0).getInputTokens()).isNull();
        assertThat(calls.get(1).getCachedInputTokens()).isEqualTo(200L);
        assertThat(calls.get(1).getReasoningTokens()).isEqualTo(300L);
    }

    @Test
    void 이미_정리된_Job은_결과를_저장하지_않고_실행_기록만_남긴다() {
        for (QuizJudgeJobStatus status : new QuizJudgeJobStatus[] {QuizJudgeJobStatus.UNKNOWN,
                QuizJudgeJobStatus.FAILED, QuizJudgeJobStatus.PENDING}) {
            QuizJudgeJob job = runningJob();
            ReflectionTestUtils.setField(job, "status", status);
            stubCompletion(job);

            assertThat(store.completeRunning(1L, result(settings), OPEN))
                    .isEqualTo(QuizJudgeEvaluationStore.Completion.NOT_RUNNING);
            assertThat(job.getStatus()).isEqualTo(status);
            assertThat(job.getHttpRequestCount()).isEqualTo(2);
        }
        verify(resultRepository, never()).saveAndFlush(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void series와_다른_설정이나_변조된_결과는_저장하지_않는다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        QuizJudgeResult otherSettings = result(QuizJudgeSettings.current("model-b", "medium", 8192, 200, objectMapper));
        QuizJudgeResult valid = result(settings);
        QuizJudgeResult tampered = new QuizJudgeResult(valid.evaluation(), valid.settings(), valid.inputSha256(),
                valid.settingsFingerprint(), "f".repeat(64), valid.execution());

        for (QuizJudgeResult result : List.of(otherSettings, tampered)) {
            assertThat(store.completeRunning(1L, result, OPEN))
                    .isEqualTo(QuizJudgeEvaluationStore.Completion.IDENTITY_MISMATCH);
        }
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.RUNNING);
        verify(resultRepository, never()).saveAndFlush(any());
    }

    @Test
    void 내용과_hash가_서로_맞아도_문항_번호나_reason_계약을_어긴_결과는_저장하지_않는다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        List<List<QuizJudgeEvaluation.QuestionEvaluation>> invalid = List.of(
                List.of(question(0, "근거"), question(1, "근거"), question(99, "근거")),
                List.of(question(0, "근거"), question(0, "근거"), question(1, "근거")),
                List.of(question(0, "근거"), question(1, "근거")),
                List.of(question(0, "근거"), question(1, "가".repeat(201)), question(2, "근거")),
                List.of(question(0, "근거"), question(1, " "), question(2, "근거")));

        for (List<QuizJudgeEvaluation.QuestionEvaluation> questions : invalid) {
            assertThat(store.completeRunning(1L, consistentResult(questions), OPEN))
                    .isEqualTo(QuizJudgeEvaluationStore.Completion.INVALID_RESULT);
        }
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.RUNNING);
        verify(resultRepository, never()).saveAndFlush(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void 저장_경계의_reason_한도는_series에_고정한_값이다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        given(resultRepository.saveAndFlush(any(QuizJudgeStoredResult.class))).willAnswer(invocation -> {
            QuizJudgeStoredResult stored = invocation.getArgument(0);
            ReflectionTestUtils.setField(stored, "resultId", 801L);
            return stored;
        });

        assertThat(store.completeRunning(1L, consistentResult(List.of(question(2, "근거"), question(0, "가".repeat(200)),
                question(1, "근거"))), OPEN)).isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);
        assertThat(savedItems()).extracting(QuizJudgeStoredResultItem::getQuestionNo).startsWith(0, 0, 0, 0, 0, 0, 0, 1);
    }

    @Test
    void rubric_항목이_빠지거나_겹친_문항은_평가로_만들_수_없다() {
        Map<JudgeRubric, QuizJudgeEvaluation.RubricResult> six = new EnumMap<>(JudgeRubric.class);
        for (JudgeRubric rubric : JudgeRubric.values()) {
            if (rubric != JudgeRubric.TEXT_FAITHFULNESS) {
                six.put(rubric, new QuizJudgeEvaluation.RubricResult(JudgeVerdict.PASS, "근거"));
            }
        }
        assertThatThrownBy(() -> new QuizJudgeEvaluation.QuestionEvaluation(0, six))
                .isInstanceOf(IllegalArgumentException.class);
        // 항목은 JudgeRubric 키의 Map이라 같은 항목이 두 번 들어갈 수 없다. 응답의 중복 키는 Parser가 거부한다.
        Map<JudgeRubric, QuizJudgeEvaluation.RubricResult> duplicated = new EnumMap<>(question(0, "근거").items());
        duplicated.put(JudgeRubric.UNIQUE_ANSWER, new QuizJudgeEvaluation.RubricResult(JudgeVerdict.FAIL, "다시"));
        assertThat(duplicated).hasSize(7);
    }

    @Test
    void RUNNING만_FAILED_UNKNOWN으로_끝내고_실행_기록을_남긴다() {
        QuizJudgeJob job = runningJob();
        given(jobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(job));

        assertThat(store.finishRunning(1L, QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.EXECUTION_INTERRUPTED,
                execution(), NOW)).isTrue();
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(job.getFailureCode()).isEqualTo(QuizJudgeFailure.EXECUTION_INTERRUPTED);
        assertThat(job.getHttpRequestCount()).isEqualTo(2);

        assertThat(store.finishRunning(1L, QuizJudgeJobStatus.FAILED, QuizJudgeFailure.PROVIDER_ERROR, execution(),
                NOW)).isFalse();
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        verify(callRepository).saveAll(any());
    }

    @Test
    void Worker는_조건부_UPDATE로만_점유하고_PENDING_제출_실패를_끝낸다() {
        given(jobRepository.claim(1L, QuizJudgeJobStatus.PENDING, QuizJudgeJobStatus.RUNNING, NOW)).willReturn(1, 0);
        assertThat(store.claim(1L, NOW)).isTrue();
        assertThat(store.claim(1L, NOW)).isFalse();

        given(jobRepository.failPending(1L, QuizJudgeJobStatus.PENDING, QuizJudgeJobStatus.FAILED,
                QuizJudgeFailure.DISPATCH_REJECTED, NOW)).willReturn(1);
        assertThat(store.failPending(1L, QuizJudgeFailure.DISPATCH_REJECTED, NOW)).isTrue();
    }

    @Test
    void Worker용_입력은_revision을_다시_검증하고_series의_입력_hash와_같아야_한다() {
        given(jobRepository.findKeys(1L)).willReturn(Optional.of(new QuizJudgeJobKeys(1L, QUIZ_ID, REVISION_ID,
                SERIES_ID)));
        QuizJudgeSeries series = series(1, settings);
        given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.of(series));

        assertThat(store.loadInput(1L)).isEqualTo(new QuizJudgeEvaluationStore.JudgeRun(input(),
                QuizJudgeFingerprint.settingsFingerprint(settings)));

        ReflectionTestUtils.setField(series, "inputSha256", "0".repeat(64));
        assertError(() -> store.loadInput(1L), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }

    private QuizJudgeJob create() {
        return admit().job();
    }

    private QuizJudgeEvaluationStore.Admission admit() {
        return store.create(CREATOR_ID, MEMBER_ID, QUIZ_ID, REVISION_ID, REQUEST_ID, NOW);
    }

    @Test
    void snapshot_한도가_300이고_hash가_맞아도_201자_reason은_저장하지_않는다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        var wider = QuizJudgeSettings.current("model-a", "medium", 8192, 300, objectMapper);
        given(seriesRepository.findByIdForUpdate(SERIES_ID)).willReturn(Optional.of(series(1, wider)));
        var evaluation = new QuizJudgeEvaluation(List.of(question(0, "가".repeat(201)), question(1, "근거"),
                question(2, "근거")));
        String inputSha = QuizJudgeFingerprint.inputSha256(input());
        String settingsSha = QuizJudgeFingerprint.settingsFingerprint(wider);
        var value = new QuizJudgeResult(evaluation, wider, inputSha, settingsSha,
                QuizJudgeFingerprint.resultSha256(inputSha, settingsSha, wider.policyVersion(), evaluation), execution());

        assertThat(store.completeRunning(1L, value, OPEN)).isEqualTo(QuizJudgeEvaluationStore.Completion.INVALID_RESULT);
        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.RUNNING);
        verify(resultRepository, never()).saveAndFlush(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void API_대상은_소유권을_확인하고_없는_Quiz와_revision을_구분한다() {
        store.requireTarget(CREATOR_ID, QUIZ_ID, REVISION_ID);
        assertError(() -> store.requireTarget(999L, QUIZ_ID, REVISION_ID), CommonErrorCode.FORBIDDEN);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.empty());
        assertError(() -> store.requireTarget(CREATOR_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_NOT_FOUND);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(revisionRepository.findById(REVISION_ID)).willReturn(Optional.empty());
        assertError(() -> store.requireTarget(CREATOR_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_REVISION_NOT_FOUND);
        verify(judge, never()).judge(any());
    }

    @Test
    void 잠금을_얻은_뒤_deadline을_넘기면_UNKNOWN으로_끝내고_부분_비용과_호출_기록만_남긴다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        AtomicLong nanos = new AtomicLong(149);
        given(jobRepository.findByIdForUpdate(1L)).willAnswer(invocation -> {
            nanos.set(151);
            return Optional.of(job);
        });

        assertThat(store.completeRunning(1L, result(settings),
                new QuizJudgeEvaluationStore.ExecutionDeadline(150, nanos::get)))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.DEADLINE_EXCEEDED);

        assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(job.getFailureCode()).isEqualTo(QuizJudgeFailure.EXECUTION_TIMEOUT);
        assertThat(job.getCompletedAt()).isEqualTo(NOW);
        assertThat(job.getHttpRequestCount()).isEqualTo(2);
        assertThat(job.getEstimatedCostUsd()).isEqualByComparingTo("0.0067");
        assertThat(job.getCostComplete()).isFalse();
        assertThat(job.getPricingVersion()).isEqualTo("pricing-v1");
        assertThat(savedCalls()).extracting(QuizJudgeJobCall::getCallNo).containsExactly(1, 2);
        verify(resultRepository, never()).saveAndFlush(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void 잠금을_얻는_중_중단되면_UNKNOWN으로_끝내고_interrupt와_실행_기록을_보존한다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        given(jobRepository.findByIdForUpdate(1L)).willAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return Optional.of(job);
        });
        try {
            assertThat(store.completeRunning(1L, result(settings), OPEN))
                    .isEqualTo(QuizJudgeEvaluationStore.Completion.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
            assertThat(job.getFailureCode()).isEqualTo(QuizJudgeFailure.EXECUTION_INTERRUPTED);
            assertThat(job.getCompletedAt()).isEqualTo(NOW);
            assertThat(job.getEstimatedCostUsd()).isEqualByComparingTo("0.0067");
            assertThat(job.getCostComplete()).isFalse();
            assertThat(savedCalls()).hasSize(2);
            verify(resultRepository, never()).saveAndFlush(any());
            verify(itemRepository, never()).saveAll(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 이미_종료된_Job은_deadline과_interrupt가_있어도_상태와_완료_시각을_덮어쓰지_않는다() {
        QuizJudgeJob job = runningJob();
        Instant earlier = NOW.minusSeconds(10);
        job.finish(QuizJudgeJobStatus.UNKNOWN, QuizJudgeFailure.STALE_RUNNING, earlier);
        stubCompletion(job);
        Thread.currentThread().interrupt();
        try {
            assertThat(store.completeRunning(1L, result(settings),
                    new QuizJudgeEvaluationStore.ExecutionDeadline(0, () -> 1L)))
                    .isEqualTo(QuizJudgeEvaluationStore.Completion.NOT_RUNNING);
            assertThat(job.getStatus()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
            assertThat(job.getFailureCode()).isEqualTo(QuizJudgeFailure.STALE_RUNNING);
            assertThat(job.getCompletedAt()).isEqualTo(earlier);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(savedCalls()).hasSize(2);
            verify(resultRepository, never()).saveAndFlush(any());
            verify(itemRepository, never()).saveAll(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 결과와_Job의_완료_시각은_잠금_획득_이후_Store_clock에서_취득한다() {
        QuizJudgeJob job = runningJob();
        stubCompletion(job);
        Clock storeClock = mock(Clock.class);
        Instant completed = NOW.plusSeconds(20);
        given(storeClock.instant()).willReturn(completed);
        QuizJudgeEvaluationStore timedStore = new QuizJudgeEvaluationStore(quizRepository, revisionRepository,
                decisionRepository, revisionReader, seriesRepository, jobRepository, callRepository, resultRepository,
                itemRepository, judge, properties, storeClock);
        given(resultRepository.saveAndFlush(any())).willAnswer(invocation -> {
            QuizJudgeStoredResult stored = invocation.getArgument(0);
            ReflectionTestUtils.setField(stored, "resultId", 801L);
            return stored;
        });

        assertThat(timedStore.completeRunning(1L, result(settings), OPEN))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);

        ArgumentCaptor<QuizJudgeStoredResult> stored = ArgumentCaptor.forClass(QuizJudgeStoredResult.class);
        InOrder order = inOrder(quizRepository, seriesRepository, jobRepository, storeClock, resultRepository);
        order.verify(quizRepository).findByIdForUpdate(QUIZ_ID);
        order.verify(seriesRepository).findByIdForUpdate(SERIES_ID);
        order.verify(jobRepository).findByIdForUpdate(1L);
        order.verify(storeClock).instant();
        order.verify(resultRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getCompletedAt()).isEqualTo(completed);
        assertThat(job.getCompletedAt()).isEqualTo(completed);
    }

    private void stubCompletion(QuizJudgeJob job) {
        given(jobRepository.findKeys(1L)).willReturn(Optional.of(new QuizJudgeJobKeys(1L, QUIZ_ID, REVISION_ID,
                SERIES_ID)));
        given(seriesRepository.findByIdForUpdate(SERIES_ID)).willReturn(Optional.of(series(1, settings)));
        given(jobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(job));
    }

    private QuizJudgeJob runningJob() {
        QuizJudgeJob job = new QuizJudgeJob(series(1, settings), CREATOR_ID, MEMBER_ID, REQUEST_ID, 1, NOW);
        ReflectionTestUtils.setField(job, "jobId", 1L);
        ReflectionTestUtils.setField(job, "status", QuizJudgeJobStatus.RUNNING);
        ReflectionTestUtils.setField(job, "startedAt", NOW);
        return job;
    }

    private QuizJudgeSeries series(int allocatedAttempts, QuizJudgeSettings seriesSettings) {
        QuizJudgeSeries series = new QuizJudgeSeries(QUIZ_ID, REVISION_ID, quiz.getContentTextSha256(),
                revision.getRevisionSha256(), QuizJudgeFingerprint.inputSha256(input()), seriesSettings, NOW);
        ReflectionTestUtils.setField(series, "seriesId", SERIES_ID);
        ReflectionTestUtils.setField(series, "allocatedAttempts", allocatedAttempts);
        return series;
    }

    private QuizJudgeResult result(QuizJudgeSettings resultSettings) {
        QuizJudgeEvaluation evaluation = new QuizJudgeEvaluationValidator().validate(
                new QuizJudgeResponseParser(objectMapper).parse(QuizJudgeFixtures.allPass(3)), 3, 200);
        String inputSha256 = QuizJudgeFingerprint.inputSha256(input());
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(resultSettings);
        return new QuizJudgeResult(evaluation, resultSettings, inputSha256, settingsFingerprint,
                QuizJudgeFingerprint.resultSha256(inputSha256, settingsFingerprint, resultSettings.policyVersion(),
                        evaluation), execution());
    }

    /** 내용으로 hash를 계산해 내용과 hash가 서로 맞는 결과. 계약 검증만 걸리게 한다. */
    private QuizJudgeResult consistentResult(List<QuizJudgeEvaluation.QuestionEvaluation> questions) {
        QuizJudgeEvaluation evaluation = new QuizJudgeEvaluation(questions);
        String inputSha256 = QuizJudgeFingerprint.inputSha256(input());
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(settings);
        QuizJudgeEvaluation sorted = new QuizJudgeEvaluation(questions.stream()
                .sorted(Comparator.comparingInt(QuizJudgeEvaluation.QuestionEvaluation::questionNo))
                .toList());
        return new QuizJudgeResult(evaluation, settings, inputSha256, settingsFingerprint,
                QuizJudgeFingerprint.resultSha256(inputSha256, settingsFingerprint, settings.policyVersion(), sorted),
                execution());
    }

    private static QuizJudgeEvaluation.QuestionEvaluation question(int questionNo, String reason) {
        Map<JudgeRubric, QuizJudgeEvaluation.RubricResult> items = new EnumMap<>(JudgeRubric.class);
        for (JudgeRubric rubric : JudgeRubric.values()) {
            items.put(rubric, new QuizJudgeEvaluation.RubricResult(JudgeVerdict.PASS, reason));
        }
        return new QuizJudgeEvaluation.QuestionEvaluation(questionNo, items);
    }

    private QuizJudgeExecution execution() {
        return new QuizJudgeExecution("model-a", List.of(
                new QuizJudgeExecution.Exchange(429, null, null, null),
                new QuizJudgeExecution.Exchange(200, "resp_1", "model-a-2026",
                        new JudgeUsage(1000L, 200L, 500L, 300L, 1500L))),
                new QuizJudgeExecution.CostEstimate(new BigDecimal("0.0067"), "pricing-v1", false));
    }

    @SuppressWarnings("unchecked")
    private List<QuizJudgeStoredResultItem> savedItems() {
        ArgumentCaptor<List<QuizJudgeStoredResultItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<QuizJudgeJobCall> savedCalls() {
        ArgumentCaptor<List<QuizJudgeJobCall>> captor = ArgumentCaptor.forClass(List.class);
        verify(callRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    /** 3문항·보기 4개, 문항 번호와 보기 순서는 저장된 그대로. */
    private static List<QuizQuestionContent> contents() {
        List<QuizQuestionContent> contents = new ArrayList<>();
        for (QuizJudgeInput.Question question : QuizJudgeFixtures.input(3).questions()) {
            contents.add(new QuizQuestionContent(question.questionNo(), question.question(), question.options(),
                    question.correctOptionIndex(), question.explanation(), question.sourceEvidence()));
        }
        return contents;
    }

    private static QuizJudgeInput input() {
        return new QuizJudgeInput(QuizFixtures.CONTENT_TEXT, QuizJudgeFixtures.input(3).questions());
    }

    private static QuizRevision revision() {
        QuizRevision revision = new QuizRevision(QUIZ_ID, 1, "제목", "v1", 3, 4, "r".repeat(64), NOW);
        ReflectionTestUtils.setField(revision, "revisionId", REVISION_ID);
        return revision;
    }

    private static void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}

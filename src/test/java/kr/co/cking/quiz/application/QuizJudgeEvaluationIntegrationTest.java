package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.application.dto.QuizJudgeEvaluationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizJudgeSeries;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeFixtures;
import kr.co.cking.quiz.judge.QuizJudgeResponseParser;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import kr.co.cking.quiz.judge.QuizJudgeSettings;
import kr.co.cking.quiz.repository.QuizJudgeSeriesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL로 Quiz Judge 평가 접수와 저장 계약을 확인한다(이슈 #529). 실행 제출을 막아 Worker와 Provider는 돌지
 * 않는다. API key는 접수 검사만 통과하도록 넣은 가짜 값이다.
 */
@SpringBootTest
@TestPropertySource(properties = {"cking.quiz.judge.enabled=true", "cking.quiz.judge.api-key=test-not-used"})
class QuizJudgeEvaluationIntegrationTest {

    /** 지나지 않는 deadline. */
    private static final QuizJudgeEvaluationStore.ExecutionDeadline OPEN =
            new QuizJudgeEvaluationStore.ExecutionDeadline(Long.MAX_VALUE, System::nanoTime);

    /** 접수·저장 계약만 확인하도록 실행 제출을 막는다. Worker 경로는 QuizJudgeWorkerIntegrationTest가 맡는다. */
    @TestBean
    private QuizJudgeDispatcher dispatcher;

    static QuizJudgeDispatcher dispatcher() {
        return Mockito.mock(QuizJudgeDispatcher.class);
    }

    @Autowired
    private QuizJudgeEvaluationService evaluationService;
    @Autowired
    private QuizJudgeEvaluationStore store;
    @Autowired
    private QuizJudgeSeriesRepository seriesRepository;
    @Autowired
    private QuizDraftService draftService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private kr.co.cking.quiz.repository.QuizJudgeStoredResultItemRepository itemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private QuizIntegrationData data;
    private Creator creator;
    private QuizDraft draft;

    @BeforeEach
    void setUp() {
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
        draft = draft();
    }

    /** 판정 → 결과 → 호출 기록 → Job → series 순서로 지운 뒤 Quiz·Creator를 지운다. */
    @AfterEach
    void cleanUp() {
        Long creatorId = creator.getCreatorId();
        jdbcTemplate.update("DELETE i FROM quiz_judge_result_item i JOIN quiz_judge_result r ON i.result_id = r.result_id "
                + "JOIN quiz_judge_job j ON r.job_id = j.job_id WHERE j.creator_id = ?", creatorId);
        jdbcTemplate.update("DELETE r FROM quiz_judge_result r JOIN quiz_judge_job j ON r.job_id = j.job_id "
                + "WHERE j.creator_id = ?", creatorId);
        jdbcTemplate.update("DELETE c FROM quiz_judge_job_call c JOIN quiz_judge_job j ON c.job_id = j.job_id "
                + "WHERE j.creator_id = ?", creatorId);
        jdbcTemplate.update("DELETE FROM quiz_judge_job WHERE creator_id = ?", creatorId);
        jdbcTemplate.update("DELETE s FROM quiz_judge_series s JOIN quiz q ON s.quiz_id = q.quiz_id "
                + "WHERE q.creator_id = ?", creatorId);
        data.cleanUp();
    }

    @Test
    void 접수하면_series와_첫_시도가_생기고_같은_requestId는_같은_Job을_돌려준다() {
        UUID requestId = UUID.randomUUID();

        QuizJudgeEvaluationView created = request(draft, requestId);
        QuizJudgeEvaluationView replayed = request(draft, requestId);

        assertThat(created.status()).isEqualTo(QuizJudgeJobStatus.PENDING);
        assertThat(created.attempt()).isEqualTo(1);
        assertThat(replayed.evaluationId()).isEqualTo(created.evaluationId());
        assertThat(jobCount()).isEqualTo(1);
        assertThat(allocatedAttempts(draft)).isEqualTo(1);
        QuizDraft other = draft();
        assertError(() -> request(other, requestId), QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
    }

    @Test
    void 같은_requestId_동시_요청은_Job_하나만_만든다() throws Exception {
        UUID requestId = UUID.randomUUID();
        List<Object> outcomes = concurrently(8, () -> request(draft, requestId).evaluationId());

        assertThat(outcomes).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(outcomes.stream().distinct().count()).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1);
        assertThat(allocatedAttempts(draft)).isEqualTo(1);
    }

    @Test
    void 다른_requestId_동시_요청은_하나만_접수하고_실패한_접수는_시도를_쓰지_않는다() throws Exception {
        QuizDraft other = draft();
        AtomicInteger next = new AtomicInteger();
        List<Object> outcomes = concurrently(8, () -> request(next.getAndIncrement() % 2 == 0 ? draft : other,
                UUID.randomUUID()).evaluationId());

        assertThat(outcomes.stream().filter(Long.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !(outcome instanceof Long)))
                .allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS)));
        assertThat(jobCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_series s JOIN quiz_judge_job j "
                        + "ON s.series_id = j.series_id WHERE j.creator_id = ? AND s.allocated_attempts = 1",
                Integer.class, creator.getCreatorId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_series s JOIN quiz q "
                        + "ON s.quiz_id = q.quiz_id WHERE q.creator_id = ? AND s.allocated_attempts <> "
                        + "(SELECT COUNT(*) FROM quiz_judge_job j WHERE j.series_id = s.series_id)",
                Integer.class, creator.getCreatorId())).isZero();
    }

    @Test
    void 기술적_실패_뒤에만_두_번째_시도가_되고_세_번째는_한도다() {
        QuizJudgeEvaluationView first = request(draft, UUID.randomUUID());
        assertError(() -> request(draft, UUID.randomUUID()), QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS);

        fail(first.evaluationId());
        QuizJudgeEvaluationView second = request(draft, UUID.randomUUID());
        assertThat(second.attempt()).isEqualTo(2);

        fail(second.evaluationId());
        assertError(() -> request(draft, UUID.randomUUID()), QuizErrorCode.QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED);
        assertThat(allocatedAttempts(draft)).isEqualTo(2);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test
    void 오래된_PENDING은_재요청에서_FAILED_오래된_RUNNING은_조회에서_UNKNOWN이_된다() {
        UUID requestId = UUID.randomUUID();
        QuizJudgeEvaluationView pending = request(draft, requestId);
        jdbcTemplate.update("UPDATE quiz_judge_job SET requested_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) "
                + "WHERE job_id = ?", pending.evaluationId());

        QuizJudgeEvaluationView replayed = request(draft, requestId);
        assertThat(replayed.status()).isEqualTo(QuizJudgeJobStatus.FAILED);
        assertThat(replayed.failureCode()).isEqualTo(QuizJudgeFailure.STALE_PENDING);

        QuizJudgeEvaluationView second = request(draft, UUID.randomUUID());
        assertThat(store.claim(second.evaluationId(), Instant.now())).isTrue();
        jdbcTemplate.update("UPDATE quiz_judge_job SET started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) "
                + "WHERE job_id = ?", second.evaluationId());
        QuizJudgeEvaluationView found = evaluationService.find(memberId(), draft.quizId(), draft.revisionId(),
                second.evaluationId());
        assertThat(found.status()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(found.failureCode()).isEqualTo(QuizJudgeFailure.STALE_RUNNING);
    }

    @Test
    void 결과_저장은_결과와_21개_판정을_함께_남기고_완료된_series는_다시_평가하지_않는다() {
        QuizJudgeEvaluationView job = request(draft, UUID.randomUUID());
        assertThat(store.claim(job.evaluationId(), Instant.now())).isTrue();

        assertThat(store.completeRunning(job.evaluationId(), result(draft), OPEN))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);

        QuizJudgeEvaluationView found = evaluationService.find(memberId(), draft.quizId(), draft.revisionId(),
                job.evaluationId());
        assertThat(found.status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
        assertThat(found.aggregation()).isEqualTo(JudgeAggregation.ALL_PASS);
        assertThat(found.resultId()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT result_id FROM quiz_judge_result WHERE job_id = ?", Long.class, job.evaluationId()));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_result_item WHERE result_id = ?",
                Integer.class, found.resultId())).isEqualTo(21);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_job_call WHERE job_id = ?",
                Integer.class, job.evaluationId())).isEqualTo(1);
        assertError(() -> request(draft, UUID.randomUUID()), QuizErrorCode.QUIZ_JUDGE_EVALUATION_COMPLETED);
    }

    @Test
    void 정리된_뒤_늦게_끝난_결과는_저장하지_않는다() {
        QuizJudgeEvaluationView job = request(draft, UUID.randomUUID());
        assertThat(store.claim(job.evaluationId(), Instant.now())).isTrue();
        jdbcTemplate.update("UPDATE quiz_judge_job SET status = 'UNKNOWN', failure_code = 'STALE_RUNNING', "
                + "completed_at = UTC_TIMESTAMP(6) WHERE job_id = ?", job.evaluationId());

        assertThat(store.completeRunning(job.evaluationId(), result(draft), OPEN))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.NOT_RUNNING);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_result WHERE job_id = ?",
                Integer.class, job.evaluationId())).isZero();
    }

    @Test
    void DB는_진행_중_Job_둘과_세_번째_시도와_끝_상태_불일치와_series당_두_번째_결과를_거부한다() {
        QuizJudgeEvaluationView job = request(draft, UUID.randomUUID());
        Long seriesId = jdbcTemplate.queryForObject("SELECT series_id FROM quiz_judge_job WHERE job_id = ?",
                Long.class, job.evaluationId());

        assertThatThrownBy(() -> insertJob(seriesId, 2, "PENDING")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertJob(seriesId, 3, "FAILED")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz_judge_series SET allocated_attempts = 3 "
                + "WHERE series_id = ?", seriesId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz_judge_job SET status = 'FAILED' WHERE job_id = ?",
                job.evaluationId())).isInstanceOf(DataAccessException.class);

        assertThat(store.claim(job.evaluationId(), Instant.now())).isTrue();
        assertThat(store.completeRunning(job.evaluationId(), result(draft), OPEN))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO quiz_judge_result (job_id, series_id, revision_id, input_sha256, settings_fingerprint,
                    policy_version, aggregation, question_count, result_sha256, completed_at)
                SELECT job_id, series_id, revision_id, input_sha256, settings_fingerprint, policy_version,
                    aggregation, question_count, result_sha256, completed_at FROM quiz_judge_result WHERE job_id = ?
                """, job.evaluationId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void 처음_조회를_지난_같은_requestId_접수는_Quiz_잠금_뒤_기존_Job을_돌려주고_시도를_쓰지_않는다() {
        UUID requestId = UUID.randomUUID();
        QuizJudgeEvaluationView first = request(draft, requestId);

        // 두 번째 요청이 처음 조회에서 Job을 못 본 채 첫 요청 커밋 뒤 Quiz 잠금을 얻은 상황을 Store 호출로 재현한다.
        QuizJudgeEvaluationStore.Admission again = admit(draft, requestId);
        assertThat(again.created()).isFalse();
        assertThat(again.job().getJobId()).isEqualTo(first.evaluationId());
        assertThat(jobCount()).isEqualTo(1);
        assertThat(allocatedAttempts(draft)).isEqualTo(1);

        QuizDraft other = draft();
        assertError(() -> admit(other, requestId), QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);

        assertThat(store.claim(first.evaluationId(), Instant.now())).isTrue();
        assertThat(store.completeRunning(first.evaluationId(), result(draft), OPEN))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);
        assertThat(admit(draft, requestId).created()).isFalse();
        assertThat(request(draft, requestId).status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
    }

    @Test
    void 첫_시도_실패_뒤_설정이_바뀌면_새_시도는_409이고_재요청과_조회는_된다() {
        UUID requestId = UUID.randomUUID();
        QuizJudgeEvaluationView first = request(draft, requestId);
        fail(first.evaluationId());
        // 지금 설정과 다른 설정으로 series가 만들어진 상황을 fingerprint로 재현한다.
        jdbcTemplate.update("UPDATE quiz_judge_series SET settings_fingerprint = ? WHERE revision_id = ?",
                "0".repeat(64), draft.revisionId());

        assertError(() -> request(draft, UUID.randomUUID()), QuizErrorCode.QUIZ_JUDGE_SETTINGS_MISMATCH);
        assertThat(jobCount()).isEqualTo(1);
        assertThat(allocatedAttempts(draft)).isEqualTo(1);
        assertThat(request(draft, requestId).evaluationId()).isEqualTo(first.evaluationId());
        assertThat(evaluationService.find(memberId(), draft.quizId(), draft.revisionId(), first.evaluationId())
                .status()).isEqualTo(QuizJudgeJobStatus.FAILED);
    }

    @Test
    void 결과_저장_뒤_판정_저장이_실패하면_결과와_상태_변경이_모두_롤백된다() {
        QuizJudgeEvaluationView job = request(draft, UUID.randomUUID());
        assertThat(store.claim(job.evaluationId(), Instant.now())).isTrue();
        // 정상 reason 검증·결과 INSERT 뒤, 같은 TX에서 rubric CHECK 위반을 주입해 실제 DB rollback을 확인한다.
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_result WHERE job_id = ?",
                    Integer.class, job.evaluationId())).isEqualTo(1);
            Iterable<?> batch = invocation.getArgument(0);
            var first = (kr.co.cking.quiz.domain.QuizJudgeStoredResultItem) batch.iterator().next();
            jdbcTemplate.update("INSERT INTO quiz_judge_result_item (result_id, question_no, rubric, verdict, reason) "
                    + "VALUES (?, ?, 'INVALID_RUBRIC', 'PASS', '근거')", first.getResultId(), first.getQuestionNo());
            throw new AssertionError("rubric CHECK가 잘못된 INSERT를 거부하지 않았습니다.");
        }).when(itemRepository).saveAll(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> store.completeRunning(job.evaluationId(), result(draft), OPEN))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_result WHERE job_id = ?",
                Integer.class, job.evaluationId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM quiz_judge_job WHERE job_id = ?", String.class,
                job.evaluationId())).isEqualTo("RUNNING");
    }

    @Test
    void 다른_revision을_가리키는_결과는_복합_FK가_거부한다() {
        QuizJudgeEvaluationView job = request(draft, UUID.randomUUID());
        QuizDraft other = draft();
        Long seriesId = jdbcTemplate.queryForObject("SELECT series_id FROM quiz_judge_job WHERE job_id = ?",
                Long.class, job.evaluationId());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO quiz_judge_result (job_id, series_id, revision_id, input_sha256, settings_fingerprint,
                    policy_version, aggregation, question_count, result_sha256, completed_at)
                VALUES (?, ?, ?, ?, ?, 'quiz-judge-policy-v1', 'ALL_PASS', 3, ?, UTC_TIMESTAMP(6))
                """, job.evaluationId(), seriesId, other.revisionId(), "a".repeat(64), "b".repeat(64),
                "c".repeat(64))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void stale_정리가_Job을_잠근_동안_기다린_결과_저장은_정리된_상태를_보고_저장하지_않는다() throws Exception {
        QuizJudgeEvaluationView job = request(draft, UUID.randomUUID());
        assertThat(store.claim(job.evaluationId(), Instant.now())).isTrue();
        jdbcTemplate.update("UPDATE quiz_judge_job SET started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) "
                + "WHERE job_id = ?", job.evaluationId());
        QuizJudgeResult result = result(draft);
        CountDownLatch expired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> stale = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        store.expireStale(creator.getCreatorId(), Instant.now());
                        expired.countDown();
                        await(release);
                    }));
            assertThat(expired.await(30, TimeUnit.SECONDS)).isTrue();
            Future<QuizJudgeEvaluationStore.Completion> completion = executor.submit(
                    () -> store.completeRunning(job.evaluationId(), result, OPEN));
            Thread.sleep(500);
            assertThat(completion.isDone()).isFalse();

            release.countDown();
            stale.get(30, TimeUnit.SECONDS);
            assertThat(completion.get(30, TimeUnit.SECONDS)).isEqualTo(QuizJudgeEvaluationStore.Completion.NOT_RUNNING);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM quiz_judge_job WHERE job_id = ?", String.class,
                job.evaluationId())).isEqualTo("UNKNOWN");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_result WHERE job_id = ?",
                Integer.class, job.evaluationId())).isZero();
    }

    private QuizJudgeEvaluationStore.Admission admit(QuizDraft target, UUID requestId) {
        return store.create(creator.getCreatorId(), memberId(), target.quizId(), target.revisionId(),
                requestId.toString(), Instant.now());
    }

    private QuizJudgeSeries series(QuizDraft target) {
        return seriesRepository.findAll().stream()
                .filter(found -> found.getRevisionId().equals(target.revisionId()))
                .findFirst()
                .orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private QuizJudgeEvaluationView request(QuizDraft target, UUID requestId) {
        return evaluationService.request(memberId(), target.quizId(), target.revisionId(), requestId);
    }

    private QuizDraft draft() {
        return draftService.create(new QuizDraftCommand(creator.getCreatorId(), QuizGenerationFixtures.VIDEO_ID,
                "제목", new QuizGenerationInput(QuizGenerationFixtures.CONTENT_TEXT, 3, 4),
                QuizGenerationFixtures.generated(3)));
    }

    /** series에 고정한 입력·설정으로 만든 전부 pass 결과. HTTP 요청 하나의 실행 기록을 담는다. */
    private QuizJudgeResult result(QuizDraft target) {
        return result(target, "근거가 원문과 일치합니다.");
    }

    /** 모든 항목을 같은 reason으로 판정한 결과. 저장 경계 검증은 series의 reason 한도로 한다. */
    private QuizJudgeResult result(QuizDraft target, String reason) {
        QuizJudgeSeries series = series(target);
        QuizJudgeSettings settings = series.settings();
        QuizJudgeEvaluation evaluation = new QuizJudgeEvaluationValidator().validate(
                new QuizJudgeResponseParser(objectMapper).parse(
                        QuizJudgeFixtures.output(3, (no, rubric) -> new String[] {"pass", reason})),
                3, settings.reasonMaxLength());
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(settings);
        String resultSha256 = QuizJudgeFingerprint.resultSha256(series.getInputSha256(), settingsFingerprint,
                settings.policyVersion(), evaluation);
        QuizJudgeExecution execution = new QuizJudgeExecution(settings.model(),
                List.of(new QuizJudgeExecution.Exchange(200, "resp_1", settings.model(), null)), null);
        return new QuizJudgeResult(evaluation, settings, series.getInputSha256(), settingsFingerprint, resultSha256,
                execution);
    }

    private void fail(Long jobId) {
        jdbcTemplate.update("UPDATE quiz_judge_job SET status = 'FAILED', failure_code = 'PROVIDER_ERROR', "
                + "completed_at = UTC_TIMESTAMP(6) WHERE job_id = ?", jobId);
    }

    private void insertJob(Long seriesId, int attemptNo, String status) {
        jdbcTemplate.update("""
                INSERT INTO quiz_judge_job (series_id, quiz_id, revision_id, creator_id, requested_by_member_id,
                    request_id, attempt_no, status, failure_code, requested_at, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))
                """, seriesId, draft.quizId(), draft.revisionId(), creator.getCreatorId(), memberId(),
                UUID.randomUUID().toString(), attemptNo, status, "FAILED".equals(status) ? "PROVIDER_ERROR" : null,
                "FAILED".equals(status) ? Timestamp.from(Instant.now()) : null);
    }

    private Long memberId() {
        return creator.getMemberId();
    }

    private int jobCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_judge_job WHERE creator_id = ?", Integer.class,
                creator.getCreatorId());
    }

    private int allocatedAttempts(QuizDraft target) {
        return jdbcTemplate.queryForObject("SELECT allocated_attempts FROM quiz_judge_series WHERE revision_id = ?",
                Integer.class, target.revisionId());
    }

    private List<Object> concurrently(int count, Callable<Object> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, QuizErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

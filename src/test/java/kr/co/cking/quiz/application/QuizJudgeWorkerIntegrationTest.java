package kr.co.cking.quiz.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.application.dto.QuizJudgeEvaluationView;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.JudgeUsage;
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
import kr.co.cking.quiz.judge.QuizJudgeTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL과 Judge 전용 Executor로 접수 → 제출 → Worker → 저장을 확인한다(이슈 #529). Provider는 테스트용 Fake로
 * 바꿔 실제 OpenAI를 호출하지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = "cking.quiz.judge.enabled=true")
class QuizJudgeWorkerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final QuizJudgeSettings SETTINGS =
            QuizJudgeSettings.current("fake-model", "medium", 8192, 200, OBJECT_MAPPER);
    private static final QuizJudgeSettings CHANGED_SETTINGS =
            QuizJudgeSettings.current("fake-model-2", "medium", 8192, 200, OBJECT_MAPPER);

    /** Fake 동작. 테스트마다 초기화한다. */
    private static volatile CountDownLatch gate;
    private static volatile Function<QuizJudgeInput, QuizJudgeResult> behavior;
    private static volatile boolean settingsChangeAfterFirstRead;
    private static final AtomicInteger settingsReads = new AtomicInteger();
    private static final AtomicInteger judgeCalls = new AtomicInteger();
    private static final AtomicBoolean calledInTransaction = new AtomicBoolean();

    @TestBean
    private QuizJudge quizJudge;

    @Autowired
    private QuizJudgeEvaluationService evaluationService;
    @Autowired
    private QuizJudgeEvaluationProperties properties;
    @Autowired
    private QuizDraftService draftService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private QuizJudgeEvaluationStore store;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;

    private QuizIntegrationData data;
    private Creator creator;
    private QuizDraft draft;

    static QuizJudge quizJudge() {
        return new QuizJudge() {
            @Override
            public QuizJudgeResult judge(QuizJudgeInput input) {
                judgeCalls.incrementAndGet();
                calledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return behavior.apply(input);
            }

            @Override
            public QuizJudgeSettings currentSettings(int questionCount) {
                return settingsChangeAfterFirstRead && settingsReads.getAndIncrement() > 0 ? CHANGED_SETTINGS : SETTINGS;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public Duration executionTimeout() {
                return Duration.ofSeconds(150);
            }
        };
    }

    @BeforeEach
    void setUp() {
        gate = new CountDownLatch(0);
        behavior = input -> result(input, QuizJudgeFixtures.allPass(3));
        settingsChangeAfterFirstRead = false;
        settingsReads.set(0);
        judgeCalls.set(0);
        calledInTransaction.set(false);
        properties.setEnabled(true);
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
        draft = draftService.create(new QuizDraftCommand(creator.getCreatorId(), QuizGenerationFixtures.VIDEO_ID,
                "제목", new QuizGenerationInput(QuizGenerationFixtures.CONTENT_TEXT, 3, 4),
                QuizGenerationFixtures.generated(3)));
    }

    /** 대기 중인 Fake를 풀어 Worker가 끝나게 한 뒤 자식부터 지운다. */
    @AfterEach
    void cleanUp() throws InterruptedException {
        gate.countDown();
        properties.setEnabled(true);
        for (int attempt = 0; attempt < 100 && activeCount() > 0; attempt++) {
            Thread.sleep(100);
        }
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
    void 접수한_Job이_Worker에서_평가되어_결과와_판정과_실행_기록이_저장되고_Provider는_Transaction_밖에서_호출된다() {
        QuizJudgeEvaluationView created = request(UUID.randomUUID());

        QuizJudgeEvaluationView done = awaitTerminal(created.evaluationId());

        assertThat(done.status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
        assertThat(done.aggregation()).isEqualTo(JudgeAggregation.ALL_PASS);
        assertThat(count("SELECT COUNT(*) FROM quiz_judge_result_item WHERE result_id = ?", done.resultId())).isEqualTo(21);
        assertThat(count("SELECT COUNT(*) FROM quiz_judge_job_call WHERE job_id = ?", created.evaluationId())).isEqualTo(1);
        assertThat(judgeCalls).hasValue(1);
        assertThat(calledInTransaction).isFalse();
    }

    @Test
    void fail_uncertain_판정도_SUCCEEDED로_저장된다() {
        behavior = input -> result(input, QuizJudgeFixtures.output(3, (no, rubric) -> no == 0
                ? new String[] {"fail", "근거"} : new String[] {"uncertain", "근거"}));

        QuizJudgeEvaluationView done = awaitTerminal(request(UUID.randomUUID()).evaluationId());

        assertThat(done.status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
        assertThat(done.aggregation()).isEqualTo(JudgeAggregation.HAS_FAIL);
    }

    @Test
    void 시간_초과는_UNKNOWN이고_관측한_실행_기록을_남기며_다시_호출하지_않는다() {
        behavior = input -> {
            throw new QuizJudgeTimeoutException(false, new QuizJudgeExecution("fake-model", List.of(
                    new QuizJudgeExecution.Exchange(429, null, null, null),
                    new QuizJudgeExecution.Exchange(null, null, null, null)), null));
        };

        QuizJudgeEvaluationView done = awaitTerminal(request(UUID.randomUUID()).evaluationId());

        assertThat(done.status()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(done.failureCode()).isEqualTo(QuizJudgeFailure.PROVIDER_TIMEOUT);
        assertThat(done.resultId()).isNull();
        assertThat(count("SELECT http_request_count FROM quiz_judge_job WHERE job_id = ?", done.evaluationId()))
                .isEqualTo(2);
        assertThat(judgeCalls).hasValue(1);
    }

    @Test
    void 접수_뒤_설정이_바뀌면_Provider를_호출하지_않고_FAILED다() {
        settingsChangeAfterFirstRead = true;

        QuizJudgeEvaluationView done = awaitTerminal(request(UUID.randomUUID()).evaluationId());

        assertThat(done.status()).isEqualTo(QuizJudgeJobStatus.FAILED);
        assertThat(done.failureCode()).isEqualTo(QuizJudgeFailure.SETTINGS_MISMATCH);
        assertThat(judgeCalls).hasValue(0);
    }

    @Test
    void stale_정리_뒤_늦게_끝난_결과는_저장하지_않고_실행_기록만_남긴다() throws InterruptedException {
        gate = new CountDownLatch(1);
        QuizJudgeEvaluationView created = request(UUID.randomUUID());
        awaitStatus(created.evaluationId(), "RUNNING");
        jdbcTemplate.update("UPDATE quiz_judge_job SET started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) "
                + "WHERE job_id = ?", created.evaluationId());
        assertThat(find(created.evaluationId()).status()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);

        gate.countDown();
        awaitRecorded(created.evaluationId());

        QuizJudgeEvaluationView done = find(created.evaluationId());
        assertThat(done.status()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(done.failureCode()).isEqualTo(QuizJudgeFailure.STALE_RUNNING);
        assertThat(count("SELECT COUNT(*) FROM quiz_judge_result WHERE job_id = ?", created.evaluationId())).isZero();
    }

    @Test
    void 기능을_끈_뒤에도_이미_접수된_Job은_끝까지_처리된다() throws InterruptedException {
        gate = new CountDownLatch(1);
        QuizJudgeEvaluationView created = request(UUID.randomUUID());
        awaitStatus(created.evaluationId(), "RUNNING");
        properties.setEnabled(false);

        gate.countDown();

        assertThat(awaitTerminal(created.evaluationId()).status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
    }

    private QuizJudgeEvaluationView request(UUID requestId) {
        return evaluationService.request(creator.getMemberId(), draft.quizId(), draft.revisionId(), requestId);
    }

    @Test
    void 실제_Quiz_잠금을_기다리는_동안_deadline을_넘기면_결과_없이_UNKNOWN과_실행_비용만_남긴다() throws Exception {
        // Dispatcher를 거치지 않고 접수해 테스트의 monotonic clock을 쓰는 실제 Worker 하나만 실행한다.
        Long jobId = store.create(creator.getCreatorId(), creator.getMemberId(), draft.quizId(), draft.revisionId(),
                UUID.randomUUID().toString(), clock.instant()).job().getJobId();
        AtomicLong nanos = new AtomicLong();
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        behavior = input -> {
            QuizJudgeResult valid = result(input, QuizJudgeFixtures.allPass(3));
            nanos.set(Duration.ofSeconds(149).toNanos());
            providerReturned.countDown();
            return new QuizJudgeResult(valid.evaluation(), valid.settings(), valid.inputSha256(),
                    valid.settingsFingerprint(), valid.resultSha256(),
                    new QuizJudgeExecution("fake-model", valid.execution().exchanges(),
                            new QuizJudgeExecution.CostEstimate(new BigDecimal("0.0067"), "pricing-v1", false)));
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("SELECT quiz_id FROM quiz WHERE quiz_id = ? FOR UPDATE",
                                Long.class, draft.quizId());
                        locked.countDown();
                        awaitLatch(release);
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            QuizJudgeWorker timedWorker = new QuizJudgeWorker(store, quizJudge, clock, nanos::get);
            Future<?> completion = executor.submit(() -> {
                workerThread.set(Thread.currentThread());
                timedWorker.run(jobId);
            });
            assertThat(providerReturned.await(30, TimeUnit.SECONDS)).isTrue();
            // latch만으로 SQL 대기를 추정하지 않는다. 실제 완료 경로의 locking query가 MySQL 응답을 기다리는지 확인한다.
            awaitCompletionLockRead(workerThread.get(), completion);
            assertThat(completion.isDone()).isFalse();
            nanos.set(Duration.ofSeconds(151).toNanos());
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            completion.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        QuizJudgeEvaluationView done = find(jobId);
        assertThat(done.status()).isEqualTo(QuizJudgeJobStatus.UNKNOWN);
        assertThat(done.failureCode()).isEqualTo(QuizJudgeFailure.EXECUTION_TIMEOUT);
        assertThat(done.resultId()).isNull();
        assertThat(count("SELECT COUNT(*) FROM quiz_judge_result WHERE job_id = ?", jobId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM quiz_judge_result_item i JOIN quiz_judge_result r "
                + "ON i.result_id = r.result_id WHERE r.job_id = ?", jobId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM quiz_judge_job_call WHERE job_id = ?", jobId)).isEqualTo(1);
        assertThat(count("SELECT http_request_count FROM quiz_judge_job WHERE job_id = ?", jobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT estimated_cost_usd FROM quiz_judge_job WHERE job_id = ?",
                BigDecimal.class, jobId)).isEqualByComparingTo("0.0067");
        assertThat(jdbcTemplate.queryForObject("SELECT cost_complete FROM quiz_judge_job WHERE job_id = ?",
                Boolean.class, jobId)).isFalse();
        assertThat(judgeCalls).hasValue(1);
        assertThat(calledInTransaction).isFalse();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("잠금 해제 신호가 오지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitCompletionLockRead(Thread worker, Future<?> completion) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < limit) {
            StackTraceElement[] stack = worker.getStackTrace();
            boolean completing = Arrays.stream(stack).anyMatch(frame -> frame.getClassName()
                    .equals(QuizJudgeEvaluationStore.class.getName()) && frame.getMethodName().equals("completeRunning"));
            boolean lockingRead = Arrays.stream(stack).anyMatch(frame -> frame.getMethodName().equals("findByIdForUpdate"));
            boolean mysqlRead = Arrays.stream(stack).anyMatch(frame -> frame.getClassName()
                    .startsWith("com.mysql.cj.protocol") && frame.getMethodName().startsWith("read"));
            if (completing && lockingRead && mysqlRead) {
                return;
            }
            if (completion.isDone()) {
                completion.get();
                throw new AssertionError("잠금 대기를 확인하기 전에 Worker가 종료됐습니다.");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("completeRunning의 MySQL locking read 대기를 확인하지 못했습니다.");
    }

    private QuizJudgeEvaluationView find(Long jobId) {
        return evaluationService.find(creator.getMemberId(), draft.quizId(), draft.revisionId(), jobId);
    }

    private QuizJudgeEvaluationView awaitTerminal(Long jobId) {
        for (int attempt = 0; attempt < 300; attempt++) {
            QuizJudgeEvaluationView view = find(jobId);
            if (!view.status().isActive()) {
                return view;
            }
            sleep();
        }
        throw new AssertionError("평가 Job이 끝나지 않았습니다. jobId=" + jobId);
    }

    private void awaitStatus(Long jobId, String status) {
        for (int attempt = 0; attempt < 300; attempt++) {
            if (status.equals(jdbcTemplate.queryForObject("SELECT status FROM quiz_judge_job WHERE job_id = ?",
                    String.class, jobId))) {
                return;
            }
            sleep();
        }
        throw new AssertionError("평가 Job이 " + status + "가 되지 않았습니다. jobId=" + jobId);
    }

    private void awaitRecorded(Long jobId) {
        for (int attempt = 0; attempt < 300; attempt++) {
            if (jdbcTemplate.queryForObject("SELECT http_request_count FROM quiz_judge_job WHERE job_id = ?",
                    Integer.class, jobId) != null) {
                return;
            }
            sleep();
        }
        throw new AssertionError("늦은 실행 기록이 남지 않았습니다. jobId=" + jobId);
    }

    private int activeCount() {
        return count("SELECT COUNT(*) FROM quiz_judge_job WHERE creator_id = ? AND status IN ('PENDING', 'RUNNING')",
                creator.getCreatorId());
    }

    private int count(String sql, Object argument) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return value == null ? 0 : value;
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** 입력과 Fake 설정으로 hash를 맞춘 결과. HTTP 요청 하나의 사용량을 담는다. */
    private static QuizJudgeResult result(QuizJudgeInput input, String outputJson) {
        QuizJudgeEvaluation evaluation = new QuizJudgeEvaluationValidator().validate(
                new QuizJudgeResponseParser(OBJECT_MAPPER).parse(outputJson), input.questions().size(), 200);
        String inputSha256 = QuizJudgeFingerprint.inputSha256(input);
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(SETTINGS);
        return new QuizJudgeResult(evaluation, SETTINGS, inputSha256, settingsFingerprint,
                QuizJudgeFingerprint.resultSha256(inputSha256, settingsFingerprint, SETTINGS.policyVersion(), evaluation),
                new QuizJudgeExecution("fake-model", List.of(new QuizJudgeExecution.Exchange(200, "resp_1",
                        "fake-model-1", new JudgeUsage(1000L, 0L, 500L, 300L, 1500L))), null));
    }
}

package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.grounding.VideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL과 Executor로 YouTube 영상 Quiz 생성 작업을 확인한다. Provider는 테스트용 Fake로 바꾼다.
 */
@SpringBootTest
@TestPropertySource(properties = "cking.quiz.generation.enabled=true")
class QuizGenerationIntegrationTest {

    private static final String URL = "https://www.youtube.com/watch?v=" + QuizGenerationFixtures.VIDEO_ID;

    /** Provider Fake 동작. 테스트마다 초기화한다. */
    private static volatile CountDownLatch groundingGate;
    private static volatile RuntimeException groundingFailure;
    private static volatile UnaryOperator<GeneratedQuiz> generatedChange;
    private static final AtomicBoolean providerCalledInTransaction = new AtomicBoolean();
    private static final AtomicInteger groundingCalls = new AtomicInteger();
    private static final AtomicInteger generationCalls = new AtomicInteger();

    @TestBean
    private VideoGrounder videoGrounder;

    @TestBean
    private QuizGenerator quizGenerator;

    @Autowired
    private QuizGenerationService generationService;
    /** 기본은 실제 메서드를 호출한다. INSERT 뒤 실패 주입이 필요한 테스트에서만 동작을 바꾼다. */
    @MockitoSpyBean
    private QuizDraftService draftService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private QuizIntegrationData data;
    private Creator creator;

    static VideoGrounder videoGrounder() {
        return videoId -> {
            groundingCalls.incrementAndGet();
            providerCalledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            await(groundingGate);
            if (groundingFailure != null) {
                throw groundingFailure;
            }
            return new VideoGrounding(QuizGenerationFixtures.CONTENT_TEXT, List.of(), "quiz-grounding-v1", "fake");
        };
    }

    static QuizGenerator quizGenerator() {
        return input -> {
            generationCalls.incrementAndGet();
            providerCalledInTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            return generatedChange.apply(QuizGenerationFixtures.generated(input.questionCount()));
        };
    }

    @BeforeEach
    void setUp() {
        groundingGate = new CountDownLatch(0);
        groundingFailure = null;
        generatedChange = UnaryOperator.identity();
        providerCalledInTransaction.set(false);
        groundingCalls.set(0);
        generationCalls.set(0);
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
    }

    /** 대기 중인 Fake Worker가 저장하지 않고 끝나게 한 뒤, 진행 중 작업이 끝나면 지운다. */
    @AfterEach
    void cleanUp() throws InterruptedException {
        groundingFailure = new QuizProviderTimeoutException("cleanup", null);
        groundingGate.countDown();
        for (int attempt = 0; attempt < 50 && runningCount() > 0; attempt++) {
            Thread.sleep(100);
        }
        jdbcTemplate.update("DELETE FROM quiz_generation WHERE creator_id = ?", creator.getCreatorId());
        data.cleanUp();
    }

    private int runningCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quiz_generation WHERE creator_id = ? AND status IN ('PENDING', 'RUNNING')",
                Integer.class, creator.getCreatorId());
    }

    @Test
    void 접수한_작업이_Grounding_생성_저장을_거쳐_SUCCEEDED가_되고_Provider는_Transaction_밖에서_호출된다()
            throws Exception {
        QuizGenerationView accepted = generationService.request(creator.getMemberId(), command(UUID.randomUUID(), 5));

        QuizGenerationView done = awaitDone(accepted.generationId());

        assertThat(done.status()).isEqualTo(QuizGenerationStatus.SUCCEEDED);
        assertThat(done.quizId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT question_count FROM quiz_revision WHERE revision_id = ?",
                Integer.class, done.revisionId())).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT content_text FROM quiz WHERE quiz_id = ?", String.class,
                done.quizId())).isEqualTo(QuizGenerationFixtures.CONTENT_TEXT);
        assertThat(providerCalledInTransaction).isFalse();
    }

    @Test
    void 같은_requestId_재요청과_동시_요청은_작업_하나만_만든다() throws Exception {
        groundingGate = new CountDownLatch(1);
        UUID requestId = UUID.randomUUID();
        List<Object> results = runConcurrently(List.of(
                () -> generationService.request(creator.getMemberId(), command(requestId, 3)),
                () -> generationService.request(creator.getMemberId(), command(requestId, 3))));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(QuizGenerationView.class));
        assertThat(results).extracting(result -> ((QuizGenerationView) result).generationId()).containsOnly(
                ((QuizGenerationView) results.get(0)).generationId());
        assertThat(generationCount()).isEqualTo(1);
        assertThatThrownBy(() -> generationService.request(creator.getMemberId(),
                new QuizGenerationCommand(requestId, URL, "다른 제목", 3)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT));
    }

    @Test
    void 진행_중_작업이_있으면_다른_requestId의_동시_요청은_하나만_접수된다() throws Exception {
        groundingGate = new CountDownLatch(1);
        List<Object> results = runConcurrently(List.of(
                () -> generationService.request(creator.getMemberId(), command(UUID.randomUUID(), 3)),
                () -> generationService.request(creator.getMemberId(), command(UUID.randomUUID(), 3))));

        assertThat(results).filteredOn(QuizGenerationView.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_GENERATION_IN_PROGRESS));
        assertThat(generationCount()).isEqualTo(1);
    }

    @Test
    void 늦게_끝난_Worker는_UNKNOWN으로_정리된_작업에_결과를_저장하지_않는다() throws Exception {
        groundingGate = new CountDownLatch(1);
        Long generationId = generationService.request(creator.getMemberId(), command(UUID.randomUUID(), 3))
                .generationId();
        awaitStatus(generationId, QuizGenerationStatus.RUNNING);
        jdbcTemplate.update("UPDATE quiz_generation SET started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) "
                + "WHERE generation_id = ?", generationId);

        assertThat(generationService.find(creator.getMemberId(), generationId).status())
                .isEqualTo(QuizGenerationStatus.UNKNOWN);
        groundingGate.countDown();
        Thread.sleep(1_000);

        QuizGenerationView view = generationService.find(creator.getMemberId(), generationId);
        assertThat(view.status()).isEqualTo(QuizGenerationStatus.UNKNOWN);
        assertThat(view.failureCode()).isEqualTo(QuizGenerationFailure.STALE_RUNNING);
        assertThat(view.quizId()).isNull();
        assertThat(data.quizCount(creator)).isZero();
    }

    @Test
    void timeout은_UNKNOWN이고_저장_검증_실패는_Quiz_없이_FAILED다() throws Exception {
        groundingFailure = new QuizProviderTimeoutException("timeout", null);
        QuizGenerationView timedOut = awaitDone(generationService.request(creator.getMemberId(),
                command(UUID.randomUUID(), 3)).generationId());
        assertThat(timedOut.status()).isEqualTo(QuizGenerationStatus.UNKNOWN);
        assertThat(timedOut.failureCode()).isEqualTo(QuizGenerationFailure.PROVIDER_TIMEOUT);

        groundingFailure = null;
        generatedChange = generated -> {
            List<GeneratedQuestion> questions = new ArrayList<>(generated.questions());
            GeneratedQuestion first = questions.get(0);
            questions.set(0, new GeneratedQuestion("가".repeat(501), first.options(), first.correctOptionIndex(),
                    first.explanation(), first.sourceEvidence()));
            return new GeneratedQuiz(questions, generated.promptVersion());
        };
        QuizGenerationView invalid = awaitDone(generationService.request(creator.getMemberId(),
                command(UUID.randomUUID(), 3)).generationId());
        assertThat(invalid.status()).isEqualTo(QuizGenerationStatus.FAILED);
        assertThat(invalid.failureCode()).isEqualTo(QuizGenerationFailure.DRAFT_INVALID);
        assertThat(data.quizCount(creator)).isZero();
    }

    /**
     * 유효한 생성 결과로 Quiz를 실제로 INSERT한 뒤, 같은 완료 Transaction 안에서 작업을 SUCCEEDED로 바꾸기 전에 실패시킨다.
     * 실패 주입은 테스트의 spy만 쓴다. INSERT가 일어났다는 근거는 같은 Transaction에서 읽은 행 수로 남기고, 롤백은
     * 테스트 thread에서 Transaction 없이(새 연결로) 다시 확인한다.
     */
    @Test
    void Quiz_INSERT_뒤_완료_단계에서_실패하면_Quiz_행이_모두_롤백되고_작업은_SUCCEEDED가_아니다() throws Exception {
        AtomicReference<Long> insertedQuizId = new AtomicReference<>();
        AtomicReference<Long> insertedRevisionId = new AtomicReference<>();
        AtomicReference<List<Long>> insertedQuestionIds = new AtomicReference<>();
        AtomicInteger optionsVisibleInTransaction = new AtomicInteger();
        AtomicBoolean insertedInsideTransaction = new AtomicBoolean();
        org.mockito.BDDMockito.willAnswer(invocation -> {
            QuizDraft draft = (QuizDraft) invocation.callRealMethod();
            insertedQuizId.set(draft.quizId());
            insertedRevisionId.set(draft.revisionId());
            insertedInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive()
                    && countById("quiz", "quiz_id", draft.quizId()) == 1
                    && countById("quiz_revision", "revision_id", draft.revisionId()) == 1);
            insertedQuestionIds.set(jdbcTemplate.queryForList(
                    "SELECT question_id FROM quiz_question WHERE revision_id = ?", Long.class, draft.revisionId()));
            optionsVisibleInTransaction.set(optionCount(insertedQuestionIds.get()));
            throw new IllegalStateException("Quiz INSERT 뒤 작업 완료 전 실패 주입");
        }).given(draftService).create(org.mockito.ArgumentMatchers.any());

        QuizGenerationView done = awaitDone(generationService.request(creator.getMemberId(),
                command(UUID.randomUUID(), 3)).generationId());
        Thread.sleep(500);

        assertThat(insertedInsideTransaction).as("완료 Transaction 안에서 Quiz와 revision이 INSERT됐다").isTrue();
        assertThat(insertedQuestionIds.get()).hasSize(3);
        assertThat(optionsVisibleInTransaction.get()).isEqualTo(12);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(countById("quiz", "quiz_id", insertedQuizId.get())).isZero();
        assertThat(countById("quiz_revision", "revision_id", insertedRevisionId.get())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_question WHERE revision_id = ?",
                Integer.class, insertedRevisionId.get())).isZero();
        assertThat(optionCount(insertedQuestionIds.get())).isZero();
        assertThat(data.quizCount(creator)).isZero();

        assertThat(done.status()).isNotEqualTo(QuizGenerationStatus.SUCCEEDED).isEqualTo(QuizGenerationStatus.FAILED);
        assertThat(done.failureCode()).isEqualTo(QuizGenerationFailure.INTERNAL_ERROR);
        assertThat(done.quizId()).isNull();
        assertThat(groundingCalls).hasValue(1);
        assertThat(generationCalls).hasValue(1);
    }

    private int countById(String table, String idColumn, Long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Integer.class, id);
    }

    private int optionCount(List<Long> questionIds) {
        int count = 0;
        for (Long questionId : questionIds) {
            count += jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_option WHERE question_id = ?",
                    Integer.class, questionId);
        }
        return count;
    }

    @Test
    void DB는_Creator당_진행_중_작업_둘과_결과_없는_성공을_거부한다() {
        insertJob("req-a", "PENDING");
        assertThatThrownBy(() -> insertJob("req-b", "RUNNING")).isInstanceOf(DataAccessException.class);
        insertJob("req-c", "FAILED");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE quiz_generation SET status = 'SUCCEEDED', completed_at = UTC_TIMESTAMP(6) WHERE request_id = ?",
                "req-a")).isInstanceOf(DataAccessException.class);
    }

    private void insertJob(String requestId, String status) {
        boolean finished = "FAILED".equals(status);
        jdbcTemplate.update("""
                INSERT INTO quiz_generation (creator_id, requested_by_member_id, request_id, request_fingerprint, video_id,
                    title, question_count, option_count, status, failure_code, started_at, completed_at, requested_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, '제목', 3, 4, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, creator.getCreatorId(), creator.getMemberId(), requestId, "f".repeat(64),
                QuizGenerationFixtures.VIDEO_ID, status, finished ? "PROVIDER_ERROR" : null,
                "RUNNING".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                finished ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private QuizGenerationCommand command(UUID requestId, int questionCount) {
        return new QuizGenerationCommand(requestId, URL, "물의 순환", questionCount);
    }

    private int generationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_generation WHERE creator_id = ?", Integer.class,
                creator.getCreatorId());
    }

    private QuizGenerationView awaitDone(Long generationId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            QuizGenerationView view = generationService.find(creator.getMemberId(), generationId);
            if (view.status() != QuizGenerationStatus.PENDING && view.status() != QuizGenerationStatus.RUNNING) {
                return view;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Quiz 생성 작업이 끝나지 않았습니다.");
    }

    private void awaitStatus(Long generationId, QuizGenerationStatus status) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (generationService.find(creator.getMemberId(), generationId).status() == status) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("작업이 " + status + "가 되지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fake Grounding 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private List<Object> runConcurrently(List<Callable<Object>> commands) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(commands.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Callable<Object> command : commands) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return command.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    results.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    results.add(exception.getCause());
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}

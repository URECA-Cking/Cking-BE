package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.generation.FakeQuizGenerator;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.grounding.FakeVideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.sql.DataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Grounding 원문 검토 흐름(이슈 #604)을 실제 MySQL과 Executor로 검증한다. Provider는 테스트용 Fake다.
 * 단계별 오래된 작업 정리는 시각 컬럼을 과거로 옮겨 실제 SQL 조건으로 확인한다.
 *
 * <p>공용·로컬 DB를 쓰지 않도록 이 클래스 전용 MySQL·Redis 컨테이너를 띄우고 {@link DynamicPropertySource}로 연결한다.
 * Docker가 없으면 컨테이너 시작에서 실패하며 Spring Context를 만들지 않으므로 기본 datasource로 넘어가지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "cking.quiz.generation.enabled=true",
        "cking.quiz.generation.content-review.enabled=true"})
class QuizGenerationContentReviewIntegrationTest {

    private static final String URL = "https://www.youtube.com/watch?v=" + QuizGenerationFixtures.VIDEO_ID;
    private static final String REVIEWED = QuizGenerationFixtures.CONTENT_TEXT + " Creator가 고친 문장이다.";
    private static final FakeVideoGrounder GROUNDER = new FakeVideoGrounder();
    private static final List<QuizGenerationInput> GENERATION_INPUTS = new CopyOnWriteArrayList<>();
    /** 생성 Provider 응답을 붙잡아 생성 중인 단계를 만든다. */
    private static volatile CountDownLatch generationGate = new CountDownLatch(0);

    private static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "root")
            .withEnv("MYSQL_DATABASE", "cking")
            .withEnv("MYSQL_USER", "cking")
            .withEnv("MYSQL_PASSWORD", "cking")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.16-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void isolatedStores(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", QuizGenerationContentReviewIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "cking");
        registry.add("spring.datasource.password", () -> "cking");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/cking?characterEncoding=UTF-8"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false";
    }

    @TestBean
    private VideoGrounder videoGrounder;

    @TestBean
    private QuizGenerator quizGenerator;

    @MockitoSpyBean
    private QuizGenerationDispatcher dispatcher;
    @Autowired
    private QuizGenerationService generationService;
    /** 기본은 실제 메서드를 호출한다. 점유 요청 실패 주입이 필요한 테스트에서만 동작을 바꾼다. */
    @MockitoSpyBean
    private QuizGenerationStore generationStore;
    @Autowired
    private QuizGenerationWorker worker;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private kr.co.cking.quiz.application.config.QuizGenerationProperties generationProperties;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private QuizDraftService draftService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private QuizIntegrationData data;
    private final List<Creator> creators = new ArrayList<>();

    static VideoGrounder videoGrounder() {
        return GROUNDER;
    }

    static QuizGenerator quizGenerator() {
        FakeQuizGenerator generator = new FakeQuizGenerator();
        return input -> {
            GENERATION_INPUTS.add(input);
            await(generationGate);
            return generator.generate(input);
        };
    }

    @BeforeEach
    void setUp() {
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).as("이 클래스 전용 컨테이너 DB만 쓴다").isEqualTo(jdbcUrl());
        GROUNDER.reset();
        GENERATION_INPUTS.clear();
        generationGate = new CountDownLatch(0);
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
    }

    /** 진행 중인 Worker가 끝나기를 기다린 뒤 이 테스트의 작업과 Quiz를 지운다. */
    @AfterEach
    void cleanUp() throws InterruptedException {
        GROUNDER.release();
        generationGate.countDown();
        for (int attempt = 0; attempt < 50 && workingCount() > 0; attempt++) {
            Thread.sleep(100);
        }
        for (Creator creator : creators) {
            jdbcTemplate.update("DELETE FROM quiz_generation WHERE creator_id = ?", creator.getCreatorId());
        }
        data.cleanUp();
    }

    @Test
    void Grounding_뒤_검토를_기다리고_확정한_원문으로만_Quiz를_만든다() {
        Creator creator = creator();
        long id = awaitingReview(creator);

        Map<String, Object> awaiting = row(id);
        assertThat(awaiting.get("ai_content_text")).isEqualTo(QuizGenerationFixtures.CONTENT_TEXT);
        assertThat(awaiting.get("grounding_model")).isEqualTo(FakeVideoGrounder.MODEL);
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_LENGTH(grounding_facts) FROM quiz_generation "
                + "WHERE generation_id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(GENERATION_INPUTS).as("확정 전에는 Quiz를 생성하지 않는다").isEmpty();

        generationService.confirmContent(creator.getMemberId(), id, REVIEWED);
        awaitState(id, "SUCCEEDED", "GENERATING");

        Map<String, Object> done = row(id);
        String reviewedSha256 = QuizRevisionFingerprint.contentTextSha256(REVIEWED);
        assertThat(done.get("reviewed_content_text")).isEqualTo(REVIEWED);
        assertThat(done.get("reviewed_content_sha256")).isEqualTo(reviewedSha256);
        assertThat(done.get("content_confirmed_by_member_id")).isEqualTo(creator.getMemberId());
        assertThat(done.get("generation_started_at")).isNotNull();
        assertThat(done.get("ai_content_text")).as("AI 원문도 남는다").isEqualTo(QuizGenerationFixtures.CONTENT_TEXT);
        Map<String, Object> quiz = jdbcTemplate.queryForMap(
                "SELECT content_text, content_text_sha256 FROM quiz WHERE quiz_id = ?", done.get("quiz_id"));
        assertThat(quiz.get("content_text")).isEqualTo(REVIEWED);
        assertThat(quiz.get("content_text_sha256")).isEqualTo(reviewedSha256);
        assertThat(GENERATION_INPUTS).extracting(QuizGenerationInput::contentText).containsExactly(REVIEWED);
        assertThat(GROUNDER.calls()).isEqualTo(1);
    }

    @Test
    void 검토_대기도_Creator의_진행_작업이라_새_요청을_막고_취소하면_풀린다() {
        Creator creator = creator();
        long id = awaitingReview(creator);

        assertThatThrownBy(() -> request(creator)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_GENERATION_IN_PROGRESS));

        generationService.cancelReview(creator.getMemberId(), id);
        generationService.cancelReview(creator.getMemberId(), id);
        assertThat(state(id)).isEqualTo("FAILED:AWAITING_REVIEW:CANCELLED");
        assertThatThrownBy(() -> generationService.confirmContent(creator.getMemberId(), id, REVIEWED))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW));

        awaitingReview(creator);
    }

    @Test
    void 오래된_작업_정리는_검토_대기를_끝내지_않고_생성_단계는_확정과_시작_시각으로_끝낸다() {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();

        long awaiting = awaitingReview(creator);
        shift(awaiting, "started_at", "INTERVAL 1 DAY");
        expire(creator);
        assertThat(state(awaiting)).as("검토 대기는 자동으로 끝내지 않는다").isEqualTo("RUNNING:AWAITING_REVIEW:-");

        generationService.confirmContent(creator.getMemberId(), awaiting, REVIEWED);
        expire(creator);
        assertThat(state(awaiting)).as("확정 직후 시작 전 생성 단계는 그대로").isEqualTo("RUNNING:GENERATING:-");
        shift(awaiting, "content_confirmed_at", "INTERVAL 11 MINUTE");
        expire(creator);
        assertThat(state(awaiting)).isEqualTo("FAILED:GENERATING:STALE_PENDING");
        assertThat(GENERATION_INPUTS).as("시작하지 않은 생성 단계는 Provider를 부르지 않는다").isEmpty();

        long started = awaitingReview(creator);
        generationService.confirmContent(creator.getMemberId(), started, REVIEWED);
        assertThat(generationStore.claimGeneration(started, Instant.now())).isTrue();
        assertThat(generationStore.claimGeneration(started, Instant.now())).as("생성 시작은 한 번만").isFalse();
        shift(started, "content_confirmed_at", "INTERVAL 1 DAY");
        expire(creator);
        assertThat(state(started)).as("시작한 생성 단계는 확정 시각으로 끝내지 않는다").isEqualTo("RUNNING:GENERATING:-");
        shift(started, "generation_started_at", "INTERVAL 16 MINUTE");
        expire(creator);
        assertThat(state(started)).isEqualTo("UNKNOWN:GENERATING:STALE_RUNNING");

        GROUNDER.hold();
        long grounding = request(creator);
        awaitState(grounding, "RUNNING", "GROUNDING");
        shift(grounding, "started_at", "INTERVAL 16 MINUTE");
        expire(creator);
        assertThat(state(grounding)).isEqualTo("UNKNOWN:GROUNDING:STALE_RUNNING");
        GROUNDER.release();
        awaitIdle();
        assertThat(state(grounding)).as("정리된 뒤 끝난 Grounding은 결과를 저장하지 않는다")
                .isEqualTo("UNKNOWN:GROUNDING:STALE_RUNNING");
        assertThat(row(grounding).get("ai_content_text")).isNull();
    }

    @Test
    void 생성_단계_제출이_거절되면_슬롯을_풀고_Provider를_부르지_않는다() {
        doReturn(false).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);

        generationService.confirmContent(creator.getMemberId(), id, REVIEWED);

        assertThat(state(id)).isEqualTo("FAILED:GENERATING:DISPATCH_REJECTED");
        assertThat(GENERATION_INPUTS).isEmpty();
        awaitingReview(creator);
    }

    @Test
    void 같은_원문_동시_확정은_한_번만_제출하고_다른_원문은_하나만_확정된다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator same = creator();
        long sameId = awaitingReview(same);

        List<Object> sameResults = concurrently(
                () -> generationService.confirmContent(same.getMemberId(), sameId, REVIEWED),
                () -> generationService.confirmContent(same.getMemberId(), sameId, REVIEWED));

        assertThat(sameResults).noneMatch(result -> result instanceof Throwable);
        verify(dispatcher, times(1)).dispatchGeneration(sameId);
        assertThat(row(sameId).get("reviewed_content_text")).isEqualTo(REVIEWED);

        Creator different = creator();
        long differentId = awaitingReview(different);
        String other = QuizGenerationFixtures.CONTENT_TEXT + " 다른 수정이다.";

        List<Object> differentResults = concurrently(
                () -> generationService.confirmContent(different.getMemberId(), differentId, REVIEWED),
                () -> generationService.confirmContent(different.getMemberId(), differentId, other));

        assertThat(differentResults).filteredOn(result -> !(result instanceof Throwable)).hasSize(1);
        assertThat(errorCodes(differentResults)).containsExactly(QuizErrorCode.QUIZ_GENERATION_CONTENT_CONFLICT);
        assertThat(row(differentId).get("reviewed_content_text")).isIn(REVIEWED, other);
        verify(dispatcher, times(1)).dispatchGeneration(differentId);
    }

    @Test
    void 확정과_취소가_동시에_오면_하나만_성공한다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);

        List<Object> results = concurrently(
                () -> generationService.confirmContent(creator.getMemberId(), id, REVIEWED),
                () -> generationService.cancelReview(creator.getMemberId(), id));

        assertThat(results).filteredOn(result -> !(result instanceof Throwable)).hasSize(1);
        assertThat(errorCodes(results)).containsExactly(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
        assertThat(state(id)).isIn("RUNNING:GENERATING:-", "FAILED:AWAITING_REVIEW:CANCELLED");
    }

    @Test
    void 생성_Worker_두_개가_동시에_시작해도_생성_Provider는_한_번만_부른다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);
        generationService.confirmContent(creator.getMemberId(), id, REVIEWED);

        concurrently(() -> run(() -> worker.runGeneration(id)), () -> run(() -> worker.runGeneration(id)));

        assertThat(state(id)).isEqualTo("SUCCEEDED:GENERATING:-");
        assertThat(GENERATION_INPUTS).hasSize(1);
        assertThat(quizCount(creator)).isEqualTo(1);
    }

    @Test
    void 정리와_생성_시작이_겹쳐도_한쪽만_적용된다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);
        generationService.confirmContent(creator.getMemberId(), id, REVIEWED);
        shift(id, "content_confirmed_at", "INTERVAL 11 MINUTE");

        concurrently(() -> run(() -> expire(creator)), () -> run(() -> worker.runGeneration(id)));

        if (state(id).startsWith("FAILED")) {
            assertThat(state(id)).isEqualTo("FAILED:GENERATING:STALE_PENDING");
            assertThat(GENERATION_INPUTS).as("정리가 먼저면 Provider를 부르지 않는다").isEmpty();
            assertThat(quizCount(creator)).isZero();
        } else {
            assertThat(state(id)).isEqualTo("SUCCEEDED:GENERATING:-");
            assertThat(GENERATION_INPUTS).hasSize(1);
            assertThat(quizCount(creator)).isEqualTo(1);
        }
    }

    @Test
    void 생성_중_정리된_작업은_늦게_끝난_결과를_저장하지_않는다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);
        generationService.confirmContent(creator.getMemberId(), id, REVIEWED);
        generationGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> worker.runGeneration(id));
            for (int attempt = 0; attempt < 100 && GENERATION_INPUTS.isEmpty(); attempt++) {
                sleep();
            }
            assertThat(GENERATION_INPUTS).as("생성 Provider 호출 중").hasSize(1);
            shift(id, "generation_started_at", "INTERVAL 16 MINUTE");
            expire(creator);
            assertThat(state(id)).isEqualTo("UNKNOWN:GENERATING:STALE_RUNNING");

            generationGate.countDown();
            running.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(state(id)).isEqualTo("UNKNOWN:GENERATING:STALE_RUNNING");
        assertThat(row(id).get("quiz_id")).isNull();
        assertThat(quizCount(creator)).isZero();
    }

    @Test
    void Grounding_중인_작업에_다른_Worker의_점유_요청이_실패해도_정상적으로_검토_대기가_된다() {
        Creator creator = creator();
        GROUNDER.hold();
        long id = request(creator);
        awaitState(id, "RUNNING", "GROUNDING");

        doThrow(new QueryTimeoutException("Lock wait timeout")).when(generationStore).claim(eq(id), any());
        worker.run(id);
        assertThat(state(id)).as("Worker B는 상태를 바꾸지 않는다").isEqualTo("RUNNING:GROUNDING:-");

        GROUNDER.release();
        awaitState(id, "RUNNING", "AWAITING_REVIEW");
        assertThat(row(id).get("ai_content_text")).isEqualTo(QuizGenerationFixtures.CONTENT_TEXT);
        assertThat(GROUNDER.calls()).as("Worker A만 Grounding을 호출한다").isEqualTo(1);
    }

    @Test
    void 생성_중인_작업에_다른_Worker의_점유_요청이_실패해도_정상_결과를_저장한다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);
        generationService.confirmContent(creator.getMemberId(), id, REVIEWED);
        generationGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> workerA = executor.submit(() -> worker.runGeneration(id));
            for (int attempt = 0; attempt < 100 && GENERATION_INPUTS.isEmpty(); attempt++) {
                sleep();
            }
            assertThat(GENERATION_INPUTS).as("Worker A가 생성 Provider 호출 중").hasSize(1);

            doThrow(new QueryTimeoutException("Lock wait timeout")).when(generationStore)
                    .claimGeneration(eq(id), any());
            worker.runGeneration(id);
            assertThat(state(id)).as("Worker B는 상태를 바꾸지 않는다").isEqualTo("RUNNING:GENERATING:-");

            generationGate.countDown();
            workerA.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(state(id)).isEqualTo("SUCCEEDED:GENERATING:-");
        assertThat(quizCount(creator)).isEqualTo(1);
        assertThat(GENERATION_INPUTS).hasSize(1);
    }

    // Creator HTTP API(이슈 #615)

    @Test
    void API로_검토_원문을_조회하고_수정한_원문을_확정해_Quiz를_만든다() throws Exception {
        Creator creator = creator();
        long id = awaitingReview(creator);

        perform(creator, get("/api/creator/quizzes/generations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationId").value(id))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.reviewStage").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.data.contentEdited").isEmpty())
                .andExpect(jsonPath("$.data.content.aiContentText").value(QuizGenerationFixtures.CONTENT_TEXT))
                .andExpect(jsonPath("$.data.content.facts.length()").value(2))
                .andExpect(jsonPath("$.data.content.facts[0].evidenceType").value("SPEECH"))
                .andExpect(jsonPath("$.data.content.facts[1].timestampStart").isEmpty())
                .andExpect(jsonPath("$.data.content.groundingModel").value(FakeVideoGrounder.MODEL))
                .andExpect(jsonPath("$.data.content.maxLength").value(50000));

        confirm(creator, id, REVIEWED)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.reviewStage").value("GENERATING"))
                .andExpect(jsonPath("$.data.contentEdited").value(true))
                .andExpect(jsonPath("$.data.contentConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.content").doesNotExist());
        awaitState(id, "SUCCEEDED", "GENERATING");

        perform(creator, get("/api/creator/quizzes/generations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.quizId").value(row(id).get("quiz_id")))
                .andExpect(jsonPath("$.data.contentEdited").value(true))
                .andExpect(jsonPath("$.data.content").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT content_text FROM quiz WHERE quiz_id = ?", String.class,
                row(id).get("quiz_id"))).isEqualTo(REVIEWED);
        perform(creator, get("/api/creator/quizzes/generations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        // 승인 화면(revision 상세)도 생성 작업과 같은 원문 검토 정보를 준다(이슈 #622).
        String confirmedAt = com.jayway.jsonpath.JsonPath.read(perform(creator,
                get("/api/creator/quizzes/generations/" + id)).andReturn().getResponse().getContentAsString(),
                "$.data.contentConfirmedAt");
        perform(creator, get(revisionPath(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentText").value(REVIEWED))
                .andExpect(jsonPath("$.data.contentEdited").value(true))
                .andExpect(jsonPath("$.data.contentConfirmedAt").value(confirmedAt));
        perform(creator(), get(revisionPath(id))).andExpect(status().isForbidden());
    }

    @Test
    void AI_원문을_그대로_확정한_Quiz의_revision_상세는_수정되지_않음이다() throws Exception {
        Creator creator = creator();
        long id = awaitingReview(creator);

        confirm(creator, id, QuizGenerationFixtures.CONTENT_TEXT).andExpect(status().isAccepted());
        awaitState(id, "SUCCEEDED", "GENERATING");

        perform(creator, get(revisionPath(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentEdited").value(false))
                .andExpect(jsonPath("$.data.contentConfirmedAt").isNotEmpty());
    }

    @Test
    void API는_본인_작업만_다루고_취소는_멱등이며_슬롯을_푼다() throws Exception {
        Creator owner = creator();
        Creator other = creator();
        long id = awaitingReview(owner);

        confirm(other, id, REVIEWED).andExpect(status().isForbidden());
        perform(other, post("/api/creator/quizzes/generations/" + id + "/cancel")).andExpect(status().isForbidden());
        perform(other, get("/api/creator/quizzes/generations/current")).andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get("/api/creator/quizzes/generations/current")).andExpect(status().isUnauthorized());
        assertThat(state(id)).isEqualTo("RUNNING:AWAITING_REVIEW:-");

        for (int attempt = 0; attempt < 2; attempt++) {
            perform(owner, post("/api/creator/quizzes/generations/" + id + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.failureCode").value("CANCELLED"));
        }
        confirm(owner, id, REVIEWED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_NOT_AWAITING_REVIEW"));
        perform(owner, get("/api/creator/quizzes/generations/current")).andExpect(jsonPath("$.data").doesNotExist());
        awaitingReview(owner);
    }

    @Test
    void API_원문_재확정은_같은_원문이면_멱등이고_다른_원문은_409다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator creator = creator();
        long id = awaitingReview(creator);

        confirm(creator, id, REVIEWED).andExpect(status().isAccepted());
        Object confirmedAt = row(id).get("content_confirmed_at");
        confirm(creator, id, REVIEWED)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.reviewStage").value("GENERATING"));
        confirm(creator, id, REVIEWED + " 다시 고침")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_CONTENT_CONFLICT"));

        assertThat(row(id).get("content_confirmed_at")).isEqualTo(confirmedAt);
        assertThat(row(id).get("reviewed_content_text")).isEqualTo(REVIEWED);
        verify(dispatcher, times(1)).dispatchGeneration(id);
    }

    @Test
    void API_동시_확정과_확정_취소_동시_요청은_하나만_반영된다() throws Exception {
        doReturn(true).when(dispatcher).dispatchGeneration(anyLong());
        Creator same = creator();
        long sameId = awaitingReview(same);

        List<Object> sameStatuses = concurrently(() -> confirmStatus(same, sameId, REVIEWED),
                () -> confirmStatus(same, sameId, REVIEWED));

        assertThat(sameStatuses).containsExactly(202, 202);
        verify(dispatcher, times(1)).dispatchGeneration(sameId);

        Creator racing = creator();
        long racingId = awaitingReview(racing);

        List<Object> racingStatuses = concurrently(() -> confirmStatus(racing, racingId, REVIEWED),
                () -> perform(racing, post("/api/creator/quizzes/generations/" + racingId + "/cancel"))
                        .andReturn().getResponse().getStatus());

        assertThat(racingStatuses).containsAnyOf(202, 200).contains(409);
        assertThat(state(racingId)).isIn("RUNNING:GENERATING:-", "FAILED:AWAITING_REVIEW:CANCELLED");
    }

    @Test
    void API_생성_기능이_꺼져_있으면_새_확정은_503이고_취소는_된다() throws Exception {
        Creator creator = creator();
        long id = awaitingReview(creator);
        generationProperties.setEnabled(false);
        try {
            confirm(creator, id, REVIEWED)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_DISABLED"));
            perform(creator, post("/api/creator/quizzes/generations/" + id + "/cancel")).andExpect(status().isOk());
        } finally {
            generationProperties.setEnabled(true);
        }
        assertThat(state(id)).isEqualTo("FAILED:AWAITING_REVIEW:CANCELLED");
    }

    @Test
    void 기존_흐름_작업은_원문_검토_필드가_null이고_확정할_수_없다() throws Exception {
        Creator creator = creator();
        generationProperties.getContentReview().setEnabled(false);
        long id;
        try {
            id = request(creator);
        } finally {
            generationProperties.getContentReview().setEnabled(true);
        }
        awaitState(id, "SUCCEEDED", "-");

        perform(creator, get("/api/creator/quizzes/generations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.reviewStage").isEmpty())
                .andExpect(jsonPath("$.data.contentEdited").isEmpty())
                .andExpect(jsonPath("$.data.contentConfirmedAt").isEmpty());
        confirm(creator, id, REVIEWED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_NOT_AWAITING_REVIEW"));
        perform(creator, post("/api/creator/quizzes/generations/" + id + "/cancel")).andExpect(status().isConflict());
        assertThat(GENERATION_INPUTS).extracting(QuizGenerationInput::contentText)
                .containsExactly(QuizGenerationFixtures.CONTENT_TEXT);

        perform(creator, get(revisionPath(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("contentEdited")))
                .andExpect(jsonPath("$.data", hasKey("contentConfirmedAt")))
                .andExpect(jsonPath("$.data.contentEdited").value(nullValue()))
                .andExpect(jsonPath("$.data.contentConfirmedAt").value(nullValue()));
    }

    private String revisionPath(long generationId) {
        Map<String, Object> row = row(generationId);
        return "/api/creator/quizzes/" + row.get("quiz_id") + "/revisions/" + row.get("revision_id");
    }

    private ResultActions perform(Creator creator, MockHttpServletRequestBuilder request) throws Exception {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .claim("sub", String.valueOf(creator.getMemberId())).build();
        return mockMvc.perform(request.with(authentication(new JwtAuthenticationToken(jwt, List.of()))));
    }

    private ResultActions confirm(Creator creator, long id, String contentText) throws Exception {
        return perform(creator, post("/api/creator/quizzes/generations/" + id + "/content-confirmation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(
                        Map.of("contentText", contentText))));
    }

    private Object confirmStatus(Creator creator, long id, String contentText) throws Exception {
        return confirm(creator, id, contentText).andReturn().getResponse().getStatus();
    }

    private Creator creator() {
        Creator creator = data.creator();
        creators.add(creator);
        return creator;
    }

    private long request(Creator creator) {
        return generationService.request(creator.getMemberId(),
                new QuizGenerationCommand(UUID.randomUUID(), URL, "물의 순환", 3)).generationId();
    }

    private long awaitingReview(Creator creator) {
        long id = request(creator);
        awaitState(id, "RUNNING", "AWAITING_REVIEW");
        return id;
    }

    private int quizCount(Creator creator) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz WHERE creator_id = ?", Integer.class,
                creator.getCreatorId());
    }

    private static Object run(Runnable task) {
        task.run();
        return "done";
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("생성 대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void expire(Creator creator) {
        generationStore.expireStale(creator.getCreatorId(), Instant.now());
    }

    private void shift(long id, String column, String interval) {
        jdbcTemplate.update("UPDATE quiz_generation SET " + column + " = " + column + " - " + interval
                + " WHERE generation_id = ?", id);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM quiz_generation WHERE generation_id = ?", id);
    }

    /** status:review_stage:failure_code. */
    private String state(long id) {
        return jdbcTemplate.queryForObject("SELECT CONCAT(status, ':', IFNULL(review_stage, '-'), ':', "
                + "IFNULL(failure_code, '-')) FROM quiz_generation WHERE generation_id = ?", String.class, id);
    }

    private void awaitState(long id, String status, String stage) {
        String expected = status + ":" + stage + ":";
        for (int attempt = 0; attempt < 100 && !state(id).startsWith(expected); attempt++) {
            sleep();
        }
        assertThat(state(id)).startsWith(expected);
    }

    /** 정리 후에도 끝나지 않은 Worker가 결과를 쓰려다 그만둘 시간을 준다. */
    private void awaitIdle() {
        for (int attempt = 0; attempt < 20; attempt++) {
            sleep();
        }
    }

    private int workingCount() {
        if (creators.isEmpty()) {
            return 0;
        }
        String ids = String.join(",", creators.stream().map(creator -> String.valueOf(creator.getCreatorId())).toList());
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quiz_generation WHERE creator_id IN (" + ids + ") "
                + "AND (status = 'PENDING' OR (status = 'RUNNING' AND (review_stage IS NULL OR review_stage = 'GROUNDING' "
                + "OR (review_stage = 'GENERATING' AND generation_started_at IS NOT NULL))))", Integer.class);
    }

    @SafeVarargs
    private static List<Object> concurrently(Callable<Object>... calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (BusinessException exception) {
                        return exception;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Object> errorCodes(List<Object> results) {
        return results.stream().filter(BusinessException.class::isInstance)
                .map(result -> (Object) ((BusinessException) result).getErrorCode()).toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}

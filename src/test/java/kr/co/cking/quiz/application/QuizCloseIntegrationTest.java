package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties;
import kr.co.cking.quiz.application.dto.*;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.repository.*;
import kr.co.cking.ticket.application.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 별도 MySQL/Flyway에서 실제 서비스·행 잠금을 검증한다. Redis·유료 Provider는 호출하지 않는다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CreatorQuizCommandService.class, QuizCreatorLookup.class, CreatorQuizQueryService.class,
        QuizCompositionStore.class, QuizJudgeEvaluationStore.class, PublicQuizQueryService.class,
        FanQuizAttemptService.class, QuizAttemptStore.class, QuizRevisionReader.class, QuizAttemptSnapshotReader.class,
        QuizRewardDelivery.class, TicketQuizRewardService.class, QuizRewardRequestStore.class,
        QuizCloseIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizCloseIntegrationTest {
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "root").withEnv("MYSQL_DATABASE", "cking")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306).waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    static { MYSQL.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/cking");
        r.add("spring.datasource.username", () -> "root");
        r.add("spring.datasource.password", () -> "root");
    }
    @TestConfiguration static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean QuizJudgeEvaluationProperties judgeProperties() { return new QuizJudgeEvaluationProperties(); }
    }
    @Autowired CreatorQuizCommandService commands;
    @Autowired CreatorQuizQueryService creatorQueries;
    @Autowired QuizCompositionStore compositions;
    @Autowired QuizJudgeEvaluationStore judges;
    @Autowired FanQuizAttemptService fans;
    @Autowired QuizAttemptStore attempts;
    @Autowired PublicQuizQueryService publicQueries;
    @Autowired QuizRewardDelivery delivery;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired QuizRepository quizzes;
    @Autowired QuizRevisionRepository revisions;
    @Autowired QuizQuestionRepository questions;
    @Autowired QuizOptionRepository options;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean QuizApprovalPolicy approvalPolicy;
    @MockitoBean QuizRewardEarnWorker rewardWorker;
    @MockitoBean QuizJudge judge;
    Member owner, fan;
    Creator creator;
    Quiz quiz;
    QuizRevision revision;
    QuizCompositionCommand edit;

    @BeforeEach void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = members.saveAndFlush(new Member("owner" + suffix, null, null, MemberRole.USER));
        fan = members.saveAndFlush(new Member("fan" + suffix, null, null, MemberRole.USER));
        creator = creators.saveAndFlush(new Creator(owner.getMemberId(), owner.getName()));
        quiz = quizzes.saveAndFlush(new Quiz(creator.getCreatorId(), QuizFixtures.VIDEO_ID, QuizFixtures.CONTENT_TEXT, QuizFixtures.NOW));
        QuizRevision original = revisions.saveAndFlush(new QuizRevision(quiz.getQuizId(), 1, QuizFixtures.TITLE,
                QuizFixtures.PROMPT_VERSION, 2, 2, QuizFixtures.revisionSha256(), QuizFixtures.NOW));
        for (int i = 0; i < 2; i++) {
            var q = QuizFixtures.questions().get(i);
            var row = questions.saveAndFlush(new QuizQuestion(original.getRevisionId(), i, q.question(), q.correctOptionIndex(), q.explanation(), q.sourceEvidence()));
            for (int o = 0; o < 2; o++) options.saveAndFlush(new QuizOption(row.getQuestionId(), o, q.options().get(o)));
        }
        edit = new QuizCompositionCommand(UUID.randomUUID(), original.getRevisionId(), original.getRevisionSha256(),
                List.of(new QuizCompositionCommand.Selection(null, new QuizCompositionCommand.Content(
                        "수동 문항", List.of("A", "B", "C", "D"), 0, "해설", ""))));
        var admission = compositions.compose(owner.getMemberId(), quiz.getQuizId(), edit);
        revision = revisions.findById(admission.revisionId()).orElseThrow();
        commands.approveByCreator(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId(), revision.getRevisionSha256(), true);
        commands.publish(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId());
    }
    @AfterAll static void stop() { MYSQL.stop(); }
    private QuizAttemptCommand answer(UUID id) {
        return new QuizAttemptCommand(fan.getMemberId(), creator.getCreatorId(), quiz.getQuizId(), id,
                revision.getRevisionId(), List.of(new QuizAttemptCommand.Answer(0, 0)));
    }
    private void closed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_CLOSED));
    }

    @Test void 게시_마감은_승인과_Revision과_포인터를_보존하며_조회할_수_있다() {
        var first = commands.close(owner.getMemberId(), quiz.getQuizId());
        assertThat(first.publicationStatus()).isEqualTo(QuizPublicationStatus.CLOSED);
        assertThat(first.publishedRevisionId()).isEqualTo(revision.getRevisionId());
        assertThat(first.closedAt()).isNotNull();
        assertThat(commands.close(owner.getMemberId(), quiz.getQuizId())).isEqualTo(first);
        var stored = quizzes.findById(quiz.getQuizId()).orElseThrow();
        assertThat(stored.getLockedRevisionSha256()).isEqualTo(revision.getRevisionSha256());
        assertThat(stored.getLockedRevisionId()).isEqualTo(revision.getRevisionId());
        assertThat(stored.isContentLocked()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_revision_decision WHERE revision_id=?", Integer.class, revision.getRevisionId())).isEqualTo(1);
        assertThat(creatorQueries.findDetail(owner.getMemberId(), quiz.getQuizId()).closedAt()).isEqualTo(first.closedAt());
        assertThat(creatorQueries.findMine(owner.getMemberId(), PageRequest.of(0, 20)).getContent())
                .anySatisfy(q -> assertThat(q.publicationStatus()).isEqualTo(QuizPublicationStatus.CLOSED));
        assertThat(creatorQueries.findRevision(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId()).reviewStatus()).isEqualTo(QuizReviewStatus.APPROVED);
    }
    @Test void 중지_후_마감은_NULL_게시_포인터를_유지한다() {
        commands.unpublish(owner.getMemberId(), quiz.getQuizId());
        var result = commands.close(owner.getMemberId(), quiz.getQuizId());
        assertThat(result.publishedRevisionId()).isNull();
        assertThat(result.unpublishedAt()).isNotNull();
    }
    @Test void 미게시와_다른_Creator는_마감할_수_없다() {
        Quiz draft = quizzes.saveAndFlush(new Quiz(creator.getCreatorId(), QuizFixtures.VIDEO_ID, "원문", Instant.now()));
        assertThatThrownBy(() -> commands.close(owner.getMemberId(), draft.getQuizId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_CLOSE_NOT_ALLOWED));
        Member other = members.saveAndFlush(new Member("other" + UUID.randomUUID().toString().substring(0, 8), null, null, MemberRole.USER));
        creators.saveAndFlush(new Creator(other.getMemberId(), other.getName()));
        assertThatThrownBy(() -> commands.close(other.getMemberId(), quiz.getQuizId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
    }
    @Test void 마감_후_변경_명령과_신규_평가를_차단한다() {
        commands.close(owner.getMemberId(), quiz.getQuizId());
        closed(() -> commands.publish(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId()));
        closed(() -> commands.unpublish(owner.getMemberId(), quiz.getQuizId()));
        closed(() -> commands.approve(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId()));
        closed(() -> commands.reject(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId(), null));
        closed(() -> commands.approveByCreator(owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId(), revision.getRevisionSha256(), true));
        var fresh = new QuizCompositionCommand(UUID.randomUUID(), edit.baseRevisionId(), edit.baseRevisionSha256(), edit.questions());
        closed(() -> compositions.compose(owner.getMemberId(), quiz.getQuizId(), fresh));
        closed(() -> judges.create(creator.getCreatorId(), owner.getMemberId(), quiz.getQuizId(), revision.getRevisionId(), UUID.randomUUID().toString(), Instant.now()));
        var replay = compositions.compose(owner.getMemberId(), quiz.getQuizId(), edit);
        assertThat(replay.created()).isFalse();
        assertThat(replay.revisionId()).isEqualTo(revision.getRevisionId());
        verifyNoInteractions(judge);
    }
    @Test void 마감은_공개_조회와_조회해둔_문항의_신규_제출을_막는다() {
        publicQueries.findPublishedDetail(creator.getCreatorId(), quiz.getQuizId());
        commands.close(owner.getMemberId(), quiz.getQuizId());
        assertThat(publicQueries.findPublished(creator.getCreatorId(), PageRequest.of(0, 20))).isEmpty();
        assertThatThrownBy(() -> publicQueries.findPublishedDetail(creator.getCreatorId(), quiz.getQuizId())).isInstanceOf(BusinessException.class);
        closed(() -> fans.submit(answer(UUID.randomUUID())));
    }
    @Test void 마감_후_응시_replay와_결과와_PENDING_보상_재전달을_유지한다() {
        var command = answer(UUID.randomUUID());
        var result = fans.submit(command);
        assertThat(result.reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.PENDING);
        commands.close(owner.getMemberId(), quiz.getQuizId());
        var replay = fans.submit(command);
        assertThat(replay.attemptId()).isEqualTo(result.attemptId());
        assertThat(replay.snapshotSha256()).isEqualTo(result.snapshotSha256());
        assertThat(replay.questions()).isEqualTo(result.questions());
        assertThat(replay.reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.PENDING);
        assertThat(fans.findMine(fan.getMemberId(), creator.getCreatorId(), quiz.getQuizId()).reward().requestId()).isEqualTo(result.reward().requestId());
        clearInvocations(rewardWorker);
        delivery.redeliverAfter(0, 100);
        verify(rewardWorker).run(result.reward().requestId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_quiz_reward_request WHERE reward_request_id=?", Integer.class, result.reward().requestId().toString())).isEqualTo(1);
    }
    @Test void 제출이_먼저_커밋되면_마감_후에도_응시가_남는다() throws Exception { race(false); }
    @Test void 마감이_먼저_커밋되면_대기_중인_신규_제출은_거절한다() throws Exception { race(true); }
    private void race(boolean closeFirst) throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var command = answer(UUID.randomUUID());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                quizzes.findByIdForUpdate(quiz.getQuizId()).orElseThrow();
                if (closeFirst) commands.close(owner.getMemberId(), quiz.getQuizId()); else attempts.submit(command);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> {
                if (closeFirst) closed(() -> fans.submit(command)); else commands.close(owner.getMemberId(), quiz.getQuizId());
            });
            try { assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertThat(quizzes.findById(quiz.getQuizId()).orElseThrow().isClosed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_id=?", Integer.class, quiz.getQuizId())).isEqualTo(closeFirst ? 0 : 1);
    }
    @Test void 실제_DB_CHECK는_마감시각_NULL과_잠금_해제를_거절한다() {
        commands.close(owner.getMemberId(), quiz.getQuizId());
        assertThatThrownBy(() -> jdbc.update("UPDATE quiz SET closed_at=NULL WHERE quiz_id=?", quiz.getQuizId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE quiz SET content_locked=FALSE WHERE quiz_id=?", quiz.getQuizId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE quiz SET publication_status='PUBLISHED' WHERE quiz_id=?", quiz.getQuizId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}

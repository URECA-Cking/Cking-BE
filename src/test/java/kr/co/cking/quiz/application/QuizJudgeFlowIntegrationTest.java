package kr.co.cking.quiz.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.grounding.VideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounding;
import kr.co.cking.quiz.judge.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** CI MySQL 전용. 세 Provider를 모두 Fake로 바꿔 생성 → 평가 → 명시적 승인 → 게시를 연결한다. */
@SpringBootTest
@TestPropertySource(properties = {"cking.quiz.generation.enabled=true", "cking.quiz.judge.enabled=true"})
class QuizJudgeFlowIntegrationTest {
    private static final AtomicInteger groundingCalls = new AtomicInteger();
    private static final AtomicInteger generationCalls = new AtomicInteger();
    private static final AtomicInteger judgeCalls = new AtomicInteger();
    @TestBean private VideoGrounder videoGrounder;
    @TestBean private QuizGenerator quizGenerator;
    @TestBean private QuizJudge quizJudge;
    @Autowired private QuizGenerationService generation;
    @Autowired private QuizJudgeEvaluationService evaluation;
    @Autowired private CreatorQuizCommandService commands;
    @Autowired private PublicQuizQueryService publicQuery;
    @Autowired private QuizDraftService draftService;
    @Autowired private MemberRepository members;
    @Autowired private CreatorRepository creators;
    @Autowired private JdbcTemplate jdbc;
    private QuizIntegrationData data;
    private Creator creator;

    static VideoGrounder videoGrounder() {
        return videoId -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            groundingCalls.incrementAndGet();
            return new VideoGrounding(QuizGenerationFixtures.CONTENT_TEXT, List.of(), "quiz-grounding-v1", "fake");
        };
    }
    static QuizGenerator quizGenerator() {
        return input -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            generationCalls.incrementAndGet();
            return QuizGenerationFixtures.generated(input.questionCount());
        };
    }
    static QuizJudge quizJudge() {
        return new QuizJudge() {
            public java.time.Duration executionTimeout() { return java.time.Duration.ofSeconds(150); }
            public boolean isConfigured() { return true; }
            public QuizJudgeSettings currentSettings(int count) {
                return QuizJudgeSettings.current("fake", "medium", 8192, 200, new ObjectMapper());
            }
            public QuizJudgeResult judge(QuizJudgeInput input) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                judgeCalls.incrementAndGet();
                var settings = currentSettings(input.questions().size());
                var judged = new QuizJudgeEvaluationValidator().validate(new QuizJudgeResponseParser(new ObjectMapper())
                        .parse(QuizJudgeFixtures.allPass(input.questions().size())), input.questions().size(), 200);
                String inputSha = QuizJudgeFingerprint.inputSha256(input);
                String fingerprint = QuizJudgeFingerprint.settingsFingerprint(settings);
                return new QuizJudgeResult(judged, settings, inputSha, fingerprint,
                        QuizJudgeFingerprint.resultSha256(inputSha, fingerprint, settings.policyVersion(), judged), null);
            }
        };
    }
    @BeforeEach void setUp() {
        groundingCalls.set(0); generationCalls.set(0); judgeCalls.set(0);
        data = new QuizIntegrationData(members, creators, draftService, jdbc);
        creator = data.creator();
    }
    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM quiz_generation WHERE creator_id = ?", creator.getCreatorId());
        data.cleanUp();
    }
    @Test void Fake_생성부터_평가_승인_게시와_재게시까지_연결되고_replay는_재호출하지_않는다() {
        var generated = generation.request(creator.getMemberId(), new QuizGenerationCommand(UUID.randomUUID(),
                "https://youtu.be/" + QuizGenerationFixtures.VIDEO_ID, "제목", 3));
        await(() -> !List.of(QuizGenerationStatus.PENDING, QuizGenerationStatus.RUNNING)
                .contains(generation.find(creator.getMemberId(), generated.generationId()).status()));
        var draft = generation.find(creator.getMemberId(), generated.generationId());
        assertThat(draft.status()).isEqualTo(QuizGenerationStatus.SUCCEEDED);
        UUID requestId = UUID.randomUUID();
        var requested = evaluation.request(creator.getMemberId(), draft.quizId(), draft.revisionId(), requestId);
        await(() -> !evaluation.find(creator.getMemberId(), draft.quizId(), draft.revisionId(), requested.evaluationId())
                .status().isActive());
        var judged = evaluation.find(creator.getMemberId(), draft.quizId(), draft.revisionId(), requested.evaluationId());
        assertThat(judged.status()).isEqualTo(QuizJudgeJobStatus.SUCCEEDED);
        assertThat(data.decisionCount(draft.revisionId())).isZero();
        assertThat(evaluation.request(creator.getMemberId(), draft.quizId(), draft.revisionId(), requestId).evaluationId())
                .isEqualTo(requested.evaluationId());
        commands.approve(creator.getMemberId(), draft.quizId(), draft.revisionId());
        commands.publish(creator.getMemberId(), draft.quizId(), draft.revisionId());
        assertThat(publicQuery.findPublishedDetail(creator.getCreatorId(), draft.quizId()).revisionId())
                .isEqualTo(draft.revisionId());
        commands.unpublish(creator.getMemberId(), draft.quizId());
        assertThat(commands.publish(creator.getMemberId(), draft.quizId(), draft.revisionId()).publicationStatus())
                .isEqualTo(QuizPublicationStatus.PUBLISHED);
        assertThat(groundingCalls).hasValue(1);
        assertThat(generationCalls).hasValue(1);
        assertThat(judgeCalls).hasValue(1);
    }
    private static void await(BooleanSupplier condition) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > limit) throw new AssertionError("비동기 작업이 종료되지 않았습니다.");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }
}

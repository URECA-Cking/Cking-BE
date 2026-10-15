package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.PublicQuizViews;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizPublicationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 저장된 Fake 평가와 운영 승인 정책으로 승인·게시·재게시와 동시 요청을 MySQL에서 확인한다. */
@SpringBootTest
class QuizApprovalFlowIntegrationTest {

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Autowired
    private CreatorQuizCommandService commandService;
    @Autowired
    private PublicQuizQueryService publicQueryService;
    @Autowired
    private QuizDraftService draftService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private QuizIntegrationData data;
    private Creator creator;
    private QuizDraft draft;

    @BeforeEach
    void setUp() {
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
        QuizJudgeIntegrationFixture fixture = new QuizJudgeIntegrationFixture(context);
        draft = fixture.draft(creator);
        fixture.saveResult(creator, draft, kr.co.cking.quiz.judge.JudgeVerdict.PASS);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 승인_게시_게시취소_재게시와_공개_조회() {
        Long memberId = creator.getMemberId();
        assertThat(data.decisionCount(draft.revisionId())).isZero();
        assertThat(publicQueryService.findPublished(creator.getCreatorId(), PageRequest.of(0, 20))).isEmpty();

        commandService.approve(memberId, draft.quizId(), draft.revisionId());
        commandService.publish(memberId, draft.quizId(), draft.revisionId());

        PublicQuizViews.Detail detail = publicQueryService.findPublishedDetail(creator.getCreatorId(), draft.quizId());
        assertThat(detail.revisionId()).isEqualTo(draft.revisionId());
        assertThat(detail.videoId()).isEqualTo(QuizFixtures.VIDEO_ID);
        assertThat(detail.questions()).extracting(PublicQuizViews.Question::question)
                .containsExactly("물의 순환 질문 1", "물의 순환 질문 2", "물의 순환 질문 3");
        assertThat(publicQueryService.findPublished(creator.getCreatorId(), PageRequest.of(0, 20)))
                .extracting(PublicQuizViews.Summary::quizId).containsExactly(draft.quizId());

        commandService.unpublish(memberId, draft.quizId());
        assertThat(publicQueryService.findPublished(creator.getCreatorId(), PageRequest.of(0, 20))).isEmpty();
        assertThatThrownBy(() -> publicQueryService.findPublishedDetail(creator.getCreatorId(), draft.quizId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_NOT_FOUND));
        assertThat(data.decisionCount(draft.revisionId())).isEqualTo(1);

        QuizPublicationView republished = commandService.publish(memberId, draft.quizId(), draft.revisionId());
        assertThat(republished.publicationStatus()).isEqualTo(QuizPublicationStatus.PUBLISHED);
        assertThat(republished.publishedRevisionId()).isEqualTo(draft.revisionId());
    }

    @Test
    void 저장_내용이_바뀌면_승인할_수_없다() {
        jdbcTemplate.update("""
                UPDATE quiz_option o JOIN quiz_question q ON o.question_id = q.question_id
                SET o.option_text = '바뀐 보기' WHERE q.revision_id = ? AND q.question_no = 0 AND o.option_no = 1
                """, draft.revisionId());

        assertThatThrownBy(() -> commandService.approve(creator.getMemberId(), draft.quizId(), draft.revisionId()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
        assertThat(data.decisionCount(draft.revisionId())).isZero();
    }

    @Test
    void 같은_revision을_동시에_승인하고_거절하면_결정은_하나만_남는다() throws Exception {
        Long memberId = creator.getMemberId();
        List<Callable<Object>> commands = List.of(
                () -> commandService.approve(memberId, draft.quizId(), draft.revisionId()),
                () -> commandService.approve(memberId, draft.quizId(), draft.revisionId()),
                () -> commandService.reject(memberId, draft.quizId(), draft.revisionId(), null));

        List<Throwable> failures = runConcurrently(commands);

        assertThat(data.decisionCount(draft.revisionId())).isEqualTo(1);
        assertThat(failures).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_REVISION_ALREADY_DECIDED)));
    }

    @Test
    void 게시와_게시_취소가_동시에_실행돼도_상태와_포인터가_일치한다() throws Exception {
        Long memberId = creator.getMemberId();
        commandService.approve(memberId, draft.quizId(), draft.revisionId());

        for (int round = 0; round < 5; round++) {
            List<Throwable> failures = runConcurrently(List.of(
                    () -> commandService.publish(memberId, draft.quizId(), draft.revisionId()),
                    () -> commandService.unpublish(memberId, draft.quizId())));

            assertThat(failures).isEmpty();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM quiz WHERE quiz_id = ?
                      AND (publication_status = 'PUBLISHED') <> (published_revision_id IS NOT NULL)
                    """, Integer.class, draft.quizId())).isZero();
        }
    }

    private List<Throwable> runConcurrently(List<Callable<Object>> commands) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(commands.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (Callable<Object> command : commands) {
                results.add(executor.submit(() -> {
                    start.await();
                    return command.call();
                }));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Object> result : results) {
                try {
                    result.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 기능_OFF와_모델_설정_변경은_기존_평가의_승인과_게시를_막지_않는다() {
        var properties = context.getBean(kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties.class);
        var provider = context.getBean(kr.co.cking.quiz.judge.openai.OpenAiJudgeProperties.class);
        boolean enabled = properties.isEnabled();
        String model = provider.getModel();
        var pricing = provider.getPricing();
        String pricingVersion = pricing.getVersion();
        var inputPrice = pricing.getInputUsdPerMillionTokens();
        var cachedPrice = pricing.getCachedInputUsdPerMillionTokens();
        var outputPrice = pricing.getOutputUsdPerMillionTokens();
        try {
            properties.setEnabled(false);
            commandService.approve(creator.getMemberId(), draft.quizId(), draft.revisionId());
            provider.setModel("changed-model");
            pricing.setVersion("changed-pricing");
            pricing.setInputUsdPerMillionTokens(java.math.BigDecimal.ONE);
            pricing.setCachedInputUsdPerMillionTokens(java.math.BigDecimal.ZERO);
            pricing.setOutputUsdPerMillionTokens(java.math.BigDecimal.TEN);
            commandService.approve(creator.getMemberId(), draft.quizId(), draft.revisionId());
            assertThat(commandService.publish(creator.getMemberId(), draft.quizId(), draft.revisionId())
                    .publicationStatus()).isEqualTo(QuizPublicationStatus.PUBLISHED);
        } finally {
            properties.setEnabled(enabled);
            provider.setModel(model);
            pricing.setVersion(pricingVersion);
            pricing.setInputUsdPerMillionTokens(inputPrice);
            pricing.setCachedInputUsdPerMillionTokens(cachedPrice);
            pricing.setOutputUsdPerMillionTokens(outputPrice);
        }
    }

    @Test
    void 다른_revision의_결과를_승인_근거로_연결하면_DB가_거부한다() {
        var fixture = new QuizJudgeIntegrationFixture(context);
        var other = fixture.draft(creator);
        var otherResult = fixture.saveResult(creator, other, kr.co.cking.quiz.judge.JudgeVerdict.PASS);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO quiz_revision_decision "
                + "(revision_id, decision, decided_by_member_id, judge_evaluation_id, judge_evaluation_sha256, "
                + "approval_policy, approved_revision_sha256, decided_at) VALUES (?, 'APPROVED', ?, ?, ?, 'LEGACY_JUDGE_V1', "
                + "(SELECT revision_sha256 FROM quiz_revision WHERE revision_id = ?), UTC_TIMESTAMP(6))",
                draft.revisionId(), creator.getMemberId(), otherResult.getResultId(), otherResult.getResultSha256(),
                draft.revisionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(data.decisionCount(draft.revisionId())).isZero();
    }
}

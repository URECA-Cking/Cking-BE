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

/** 테스트 전용 승인 정책으로 승인·게시·게시 취소·재게시와 동시 요청을 실제 MySQL에서 확인한다. */
@SpringBootTest
class QuizApprovalFlowIntegrationTest {

    @TestBean
    private QuizApprovalPolicy approvalPolicy;

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

    static QuizApprovalPolicy approvalPolicy() {
        return new FakeQuizApprovalPolicy();
    }

    @BeforeEach
    void setUp() {
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
        draft = data.draft(creator);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 승인_게시_게시취소_재게시와_공개_조회() {
        Long memberId = creator.getMemberId();
        assertThat(publicQueryService.findPublished(creator.getCreatorId(), PageRequest.of(0, 20))).isEmpty();

        commandService.approve(memberId, draft.quizId(), draft.revisionId());
        commandService.publish(memberId, draft.quizId(), draft.revisionId());

        PublicQuizViews.Detail detail = publicQueryService.findPublishedDetail(creator.getCreatorId(), draft.quizId());
        assertThat(detail.revisionId()).isEqualTo(draft.revisionId());
        assertThat(detail.videoId()).isEqualTo(QuizFixtures.VIDEO_ID);
        assertThat(detail.questions()).extracting(PublicQuizViews.Question::question)
                .containsExactly("물이 증발하면 무엇이 되나요?", "구름은 무엇이 되어 내리나요?");
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
}

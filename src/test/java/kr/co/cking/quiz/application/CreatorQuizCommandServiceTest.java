package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizDecisionView;
import kr.co.cking.quiz.application.dto.QuizPublicationView;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.domain.QuizReviewStatus;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionDecisionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CreatorQuizCommandServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final long CREATOR_ID = 10L;
    private static final long QUIZ_ID = 1L;
    private static final long REVISION_ID = 100L;

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuizRevisionRepository revisionRepository = mock(QuizRevisionRepository.class);
    private final QuizRevisionDecisionRepository decisionRepository = mock(QuizRevisionDecisionRepository.class);
    private final QuizQuestionRepository questionRepository = mock(QuizQuestionRepository.class);
    private final QuizOptionRepository optionRepository = mock(QuizOptionRepository.class);

    private Quiz quiz;
    private QuizRevision revision;

    @BeforeEach
    void setUp() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator(CREATOR_ID)));
        quiz = QuizFixtures.quiz(QUIZ_ID, CREATOR_ID);
        revision = QuizFixtures.revision(REVISION_ID, QUIZ_ID, 1);
        given(quizRepository.findByIdForUpdate(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(revisionRepository.findById(REVISION_ID)).willReturn(Optional.of(revision));
        given(revisionRepository.findTopByQuizIdOrderByRevisionNoDesc(QUIZ_ID)).willReturn(Optional.of(revision));
        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.empty());
        given(decisionRepository.save(any(QuizRevisionDecision.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(questionRepository.findByRevisionIdOrderByQuestionNoAsc(REVISION_ID))
                .willReturn(QuizFixtures.questionRows(REVISION_ID));
        given(optionRepository.findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(anyCollection()))
                .willReturn(QuizFixtures.optionRows(REVISION_ID));
    }

    @Test
    void 운영_정책에서는_승인이_QUIZ_JUDGE_EVALUATION_REQUIRED이고_결정을_남기지_않는다() {
        CreatorQuizCommandService service = service(new JudgeNotIntegratedQuizApprovalPolicy());

        assertError(() -> service.approve(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void 운영_정책에서는_승인된_revision도_게시할_수_없다() {
        given(decisionRepository.findByRevisionId(REVISION_ID))
                .willReturn(Optional.of(QuizFixtures.approval(REVISION_ID, MEMBER_ID)));
        CreatorQuizCommandService service = service(new JudgeNotIntegratedQuizApprovalPolicy());

        assertError(() -> service.publish(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.NOT_PUBLISHED);
        assertThat(quiz.getPublishedRevisionId()).isNull();
    }

    @Test
    void 승인은_Judge_평가_근거와_함께_기록한다() {
        QuizDecisionView view = service(new FakeQuizApprovalPolicy()).approve(MEMBER_ID, QUIZ_ID, REVISION_ID);

        ArgumentCaptor<QuizRevisionDecision> saved = ArgumentCaptor.forClass(QuizRevisionDecision.class);
        verify(decisionRepository).save(saved.capture());
        assertThat(saved.getValue().isApproved()).isTrue();
        assertThat(saved.getValue().getDecidedByMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getValue().getJudgeEvaluationId()).isEqualTo(FakeQuizApprovalPolicy.JUDGE_EVALUATION_ID);
        assertThat(saved.getValue().getJudgeEvaluationSha256()).hasSize(64);
        assertThat(view.reviewStatus()).isEqualTo(QuizReviewStatus.APPROVED);
        assertThat(view.revisionId()).isEqualTo(REVISION_ID);
    }

    @Test
    void 저장_내용이_revision_해시와_다르면_정책을_부르지_않고_승인과_게시를_막는다() {
        List<QuizOption> tampered = new ArrayList<>(QuizFixtures.optionRows(REVISION_ID));
        tampered.set(0, new QuizOption(REVISION_ID * 10, 0, "바뀐 보기"));
        given(optionRepository.findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(anyCollection())).willReturn(tampered);
        QuizApprovalPolicy policy = mock(QuizApprovalPolicy.class);
        CreatorQuizCommandService service = service(policy);

        assertError(() -> service.approve(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        given(decisionRepository.findByRevisionId(REVISION_ID))
                .willReturn(Optional.of(QuizFixtures.approval(REVISION_ID, MEMBER_ID)));
        assertError(() -> service.publish(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        verifyNoInteractions(policy);
        assertThat(quiz.isPublished()).isFalse();
    }

    @Test
    void 원본_텍스트가_바뀌어도_승인을_막는다() {
        ReflectionTestUtils.setField(quiz, "contentText", "다른 원문");

        assertError(() -> service(new FakeQuizApprovalPolicy()).approve(MEMBER_ID, QUIZ_ID, REVISION_ID),
                QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
    }

    @Test
    void 이미_승인한_revision의_승인_재요청은_기존_결정을_반환하고_거절은_409다() {
        QuizRevisionDecision approval = QuizFixtures.approval(REVISION_ID, MEMBER_ID);
        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.of(approval));
        QuizApprovalPolicy policy = mock(QuizApprovalPolicy.class);
        CreatorQuizCommandService service = service(policy);

        QuizDecisionView replay = service.approve(MEMBER_ID, QUIZ_ID, REVISION_ID);

        assertThat(replay.reviewStatus()).isEqualTo(QuizReviewStatus.APPROVED);
        assertThat(replay.decidedAt()).isEqualTo(approval.getDecidedAt());
        assertError(() -> service.reject(MEMBER_ID, QUIZ_ID, REVISION_ID, null), QuizErrorCode.QUIZ_REVISION_ALREADY_DECIDED);
        verify(decisionRepository, never()).save(any());
        verifyNoInteractions(policy);
    }

    @Test
    void 거절은_Judge_평가_없이_기록하고_재요청은_기존_결정을_반환하며_승인은_409다() {
        QuizApprovalPolicy policy = mock(QuizApprovalPolicy.class);
        CreatorQuizCommandService service = service(policy);

        QuizDecisionView view = service.reject(MEMBER_ID, QUIZ_ID, REVISION_ID, "정답이 둘입니다.");

        ArgumentCaptor<QuizRevisionDecision> saved = ArgumentCaptor.forClass(QuizRevisionDecision.class);
        verify(decisionRepository).save(saved.capture());
        assertThat(saved.getValue().isApproved()).isFalse();
        assertThat(saved.getValue().getReason()).isEqualTo("정답이 둘입니다.");
        assertThat(saved.getValue().getJudgeEvaluationId()).isNull();
        assertThat(view.reviewStatus()).isEqualTo(QuizReviewStatus.REJECTED);

        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.of(saved.getValue()));
        assertThat(service.reject(MEMBER_ID, QUIZ_ID, REVISION_ID, "다른 사유").reason()).isEqualTo("정답이 둘입니다.");
        assertError(() -> service.approve(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_REVISION_ALREADY_DECIDED);
        verifyNoInteractions(policy);
    }

    @Test
    void 빈_거절_사유는_저장하지_않는다() {
        service(new FakeQuizApprovalPolicy()).reject(MEMBER_ID, QUIZ_ID, REVISION_ID, "  ");

        ArgumentCaptor<QuizRevisionDecision> saved = ArgumentCaptor.forClass(QuizRevisionDecision.class);
        verify(decisionRepository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isNull();
    }

    @Test
    void 승인된_최신_revision을_게시한다() {
        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.of(fakeApproval()));

        QuizPublicationView view = service(new FakeQuizApprovalPolicy()).publish(MEMBER_ID, QUIZ_ID, REVISION_ID);

        assertThat(view.publicationStatus()).isEqualTo(QuizPublicationStatus.PUBLISHED);
        assertThat(view.publishedRevisionId()).isEqualTo(REVISION_ID);
        assertThat(view.publishedAt()).isEqualTo(QuizFixtures.NOW);
    }

    @Test
    void 같은_revision을_게시_중이면_정책을_다시_부르지_않고_현재_상태를_반환한다() {
        quiz.publish(REVISION_ID, QuizFixtures.NOW);
        QuizApprovalPolicy policy = mock(QuizApprovalPolicy.class);

        QuizPublicationView view = service(policy).publish(MEMBER_ID, QUIZ_ID, REVISION_ID);

        assertThat(view.publishedRevisionId()).isEqualTo(REVISION_ID);
        verifyNoInteractions(policy);
    }

    @Test
    void 미승인_거절_revision은_게시할_수_없다() {
        CreatorQuizCommandService service = service(new FakeQuizApprovalPolicy());

        assertError(() -> service.publish(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_REVISION_NOT_APPROVED);
        given(decisionRepository.findByRevisionId(REVISION_ID))
                .willReturn(Optional.of(QuizFixtures.rejection(REVISION_ID, MEMBER_ID)));
        assertError(() -> service.publish(MEMBER_ID, QUIZ_ID, REVISION_ID), QuizErrorCode.QUIZ_REVISION_NOT_APPROVED);
        assertThat(quiz.isPublished()).isFalse();
    }

    @Test
    void 최신이_아닌_revision은_승인됐어도_게시할_수_없다() {
        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.of(fakeApproval()));
        given(revisionRepository.findTopByQuizIdOrderByRevisionNoDesc(QUIZ_ID))
                .willReturn(Optional.of(QuizFixtures.revision(101L, QUIZ_ID, 2)));

        assertError(() -> service(new FakeQuizApprovalPolicy()).publish(MEMBER_ID, QUIZ_ID, REVISION_ID),
                QuizErrorCode.QUIZ_REVISION_NOT_LATEST);
        assertThat(quiz.isPublished()).isFalse();
    }

    @Test
    void 승인_근거가_더_이상_유효하지_않으면_게시할_수_없다() {
        given(decisionRepository.findByRevisionId(REVISION_ID))
                .willReturn(Optional.of(QuizFixtures.approval(REVISION_ID, MEMBER_ID)));

        assertError(() -> service(new FakeQuizApprovalPolicy()).publish(MEMBER_ID, QUIZ_ID, REVISION_ID),
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        assertThat(quiz.isPublished()).isFalse();
    }

    @Test
    void 게시_취소는_승인_정책과_무관하게_포인터를_비우고_기록을_지우지_않는다() {
        quiz.publish(REVISION_ID, QuizFixtures.NOW);
        QuizApprovalPolicy policy = spy(new JudgeNotIntegratedQuizApprovalPolicy());

        QuizPublicationView view = service(policy).unpublish(MEMBER_ID, QUIZ_ID);

        assertThat(view.publicationStatus()).isEqualTo(QuizPublicationStatus.UNPUBLISHED);
        assertThat(view.publishedRevisionId()).isNull();
        assertThat(view.unpublishedAt()).isEqualTo(QuizFixtures.NOW);
        verifyNoInteractions(policy);
        verify(decisionRepository, never()).delete(any());
        verify(revisionRepository, never()).delete(any());
    }

    @Test
    void 게시_중이_아닌_Quiz의_게시_취소는_현재_상태를_반환한다() {
        QuizPublicationView view = service(new JudgeNotIntegratedQuizApprovalPolicy()).unpublish(MEMBER_ID, QUIZ_ID);

        assertThat(view.publicationStatus()).isEqualTo(QuizPublicationStatus.NOT_PUBLISHED);
    }

    @Test
    void 게시_취소_후_승인_근거가_유효한_최신_revision은_재게시할_수_있다() {
        given(decisionRepository.findByRevisionId(REVISION_ID)).willReturn(Optional.of(fakeApproval()));
        CreatorQuizCommandService service = service(new FakeQuizApprovalPolicy());
        service.publish(MEMBER_ID, QUIZ_ID, REVISION_ID);
        service.unpublish(MEMBER_ID, QUIZ_ID);

        QuizPublicationView view = service.publish(MEMBER_ID, QUIZ_ID, REVISION_ID);

        assertThat(view.publicationStatus()).isEqualTo(QuizPublicationStatus.PUBLISHED);
        assertThat(view.publishedRevisionId()).isEqualTo(REVISION_ID);
        assertThat(view.unpublishedAt()).isNull();
    }

    @Test
    void 다른_Creator의_Quiz는_403이고_없는_Quiz와_다른_Quiz의_revision은_404다() {
        CreatorQuizCommandService service = service(new FakeQuizApprovalPolicy());
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator(99L)));
        assertError(() -> service.approve(MEMBER_ID, QUIZ_ID, REVISION_ID), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.unpublish(MEMBER_ID, QUIZ_ID), CommonErrorCode.FORBIDDEN);

        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator(CREATOR_ID)));
        given(quizRepository.findByIdForUpdate(2L)).willReturn(Optional.empty());
        assertError(() -> service.publish(MEMBER_ID, 2L, REVISION_ID), QuizErrorCode.QUIZ_NOT_FOUND);

        given(revisionRepository.findById(200L)).willReturn(Optional.of(QuizFixtures.revision(200L, 2L, 1)));
        assertError(() -> service.approve(MEMBER_ID, QUIZ_ID, 200L), QuizErrorCode.QUIZ_REVISION_NOT_FOUND);
        assertError(() -> service.reject(MEMBER_ID, QUIZ_ID, 999L, null), QuizErrorCode.QUIZ_REVISION_NOT_FOUND);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void 없는_Member는_404이고_Creator가_아니면_403이다() {
        CreatorQuizCommandService service = service(new FakeQuizApprovalPolicy());
        given(memberRepository.findById(8L)).willReturn(Optional.empty());
        assertError(() -> service.unpublish(8L, QUIZ_ID), CommonErrorCode.RESOURCE_NOT_FOUND);

        given(memberRepository.findById(9L)).willReturn(Optional.of(mock(Member.class)));
        given(creatorRepository.findByMemberId(9L)).willReturn(Optional.empty());
        assertError(() -> service.unpublish(9L, QUIZ_ID), CommonErrorCode.FORBIDDEN);
    }

    private QuizRevisionDecision fakeApproval() {
        QuizApprovalPolicy.Target target = new QuizApprovalPolicy.Target(QUIZ_ID, REVISION_ID,
                quiz.getContentTextSha256(), revision.getRevisionSha256());
        return QuizRevisionDecision.approve(REVISION_ID, MEMBER_ID, FakeQuizApprovalPolicy.JUDGE_EVALUATION_ID,
                FakeQuizApprovalPolicy.evaluationSha256(target), QuizFixtures.NOW);
    }

    private CreatorQuizCommandService service(QuizApprovalPolicy policy) {
        return new CreatorQuizCommandService(
                new QuizCreatorLookup(memberRepository, creatorRepository),
                quizRepository,
                revisionRepository,
                decisionRepository,
                new QuizRevisionReader(questionRepository, optionRepository),
                policy,
                Clock.fixed(QuizFixtures.NOW, ZoneOffset.UTC));
    }

    private static Creator creator(Long creatorId) {
        Creator creator = new Creator(MEMBER_ID, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }

    private static void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}

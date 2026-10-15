package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.CreatorQuizViews;
import kr.co.cking.quiz.application.dto.PublicQuizViews;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizReviewStatus;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.repository.PublishedQuizSummary;
import kr.co.cking.quiz.repository.QuizGenerationRepository;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionDecisionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class QuizQueryServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final long CREATOR_ID = 10L;
    private static final long QUIZ_ID = 1L;

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuizRevisionRepository revisionRepository = mock(QuizRevisionRepository.class);
    private final QuizRevisionDecisionRepository decisionRepository = mock(QuizRevisionDecisionRepository.class);
    private final QuizQuestionRepository questionRepository = mock(QuizQuestionRepository.class);
    private final QuizOptionRepository optionRepository = mock(QuizOptionRepository.class);
    private final QuizGenerationRepository generationRepository = mock(QuizGenerationRepository.class);
    private final QuizRevisionReader reader = new QuizRevisionReader(questionRepository, optionRepository, revisionRepository);

    private final CreatorQuizQueryService creatorQueryService = new CreatorQuizQueryService(
            new QuizCreatorLookup(memberRepository, creatorRepository), quizRepository, revisionRepository,
            decisionRepository, reader, generationRepository);
    private final PublicQuizQueryService publicQueryService = new PublicQuizQueryService(
            creatorRepository, quizRepository, revisionRepository, reader);

    private Quiz quiz;

    @BeforeEach
    void setUp() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
        Creator creator = new Creator(MEMBER_ID, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", CREATOR_ID);
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator));
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        quiz = QuizFixtures.quiz(QUIZ_ID, CREATOR_ID);
        given(quizRepository.findById(QUIZ_ID)).willReturn(Optional.of(quiz));
        given(questionRepository.findByRevisionIdOrderByQuestionNoAsc(100L)).willReturn(QuizFixtures.questionRows(100L));
        given(optionRepository.findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(anyCollection()))
                .willReturn(QuizFixtures.optionRows(100L));
    }

    @Test
    void 내_목록은_최신_revision의_제목과_검토_상태를_보여준다() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(quizRepository.findByCreatorIdLatestFirst(CREATOR_ID, pageable))
                .willReturn(new PageImpl<>(List.of(quiz), pageable, 1));
        QuizRevision first = QuizFixtures.revision(100L, QUIZ_ID, 1);
        QuizRevision second = QuizFixtures.revision(101L, QUIZ_ID, 2);
        given(revisionRepository.findByQuizIdIn(List.of(QUIZ_ID))).willReturn(List.of(second, first));
        given(decisionRepository.findByRevisionIdIn(List.of(101L)))
                .willReturn(List.of(QuizFixtures.rejection(101L, MEMBER_ID)));

        CreatorQuizViews.Summary summary = creatorQueryService.findMine(MEMBER_ID, pageable).getContent().get(0);

        assertThat(summary.latestRevisionId()).isEqualTo(101L);
        assertThat(summary.latestRevisionNo()).isEqualTo(2);
        assertThat(summary.latestReviewStatus()).isEqualTo(QuizReviewStatus.REJECTED);
    }

    @Test
    void revision_상세는_Creator에게_정답과_원본_텍스트를_순서대로_보여준다() {
        given(revisionRepository.findByQuizIdOrderByRevisionNoAsc(QUIZ_ID))
                .willReturn(List.of(QuizFixtures.revision(100L, QUIZ_ID, 1)));
        given(decisionRepository.findByRevisionId(100L)).willReturn(Optional.empty());

        CreatorQuizViews.Revision view = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L);

        assertThat(view.reviewStatus()).isEqualTo(QuizReviewStatus.PENDING_REVIEW);
        assertThat(view.latest()).isTrue();
        assertThat(view.published()).isFalse();
        assertThat(view.contentText()).isEqualTo(QuizFixtures.CONTENT_TEXT);
        assertThat(view.questions()).extracting(question -> question.correctOptionIndex()).containsExactly(0, 1);
        assertThat(view.questions().get(1).options()).containsExactly("눈", "비");
    }

    @Test
    void revision_상세는_원문을_고쳐_확정했으면_수정됨과_확정_시각을_보여준다() {
        Instant confirmedAt = Instant.parse("2026-10-15T01:02:03.456Z");
        givenRevisions();
        given(generationRepository.findFirstByQuizIdOrderByGenerationIdAsc(QUIZ_ID))
                .willReturn(Optional.of(confirmed("AI 원문", "고친 원문", confirmedAt)));

        CreatorQuizViews.Revision view = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L);

        assertThat(view.contentEdited()).isTrue();
        assertThat(view.contentConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(view.contentText()).as("기존 필드는 Quiz 원문 그대로").isEqualTo(QuizFixtures.CONTENT_TEXT);
    }

    @Test
    void revision_상세는_AI_원문_그대로_확정했으면_수정되지_않음이다() {
        givenRevisions();
        given(generationRepository.findFirstByQuizIdOrderByGenerationIdAsc(QUIZ_ID))
                .willReturn(Optional.of(confirmed("AI 원문", "AI 원문", QuizFixtures.NOW)));

        CreatorQuizViews.Revision view = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L);

        assertThat(view.contentEdited()).isFalse();
        assertThat(view.contentConfirmedAt()).isEqualTo(QuizFixtures.NOW);
    }

    @Test
    void 원문_검토_없이_만든_Quiz와_생성_작업이_없는_Quiz는_원문_검토_정보가_null이다() {
        givenRevisions();
        QuizGeneration legacy = new QuizGeneration(CREATOR_ID, MEMBER_ID, "req", QuizFixtures.VIDEO_ID, "제목", 3,
                QuizFixtures.NOW);
        ReflectionTestUtils.setField(legacy, "status", QuizGenerationStatus.RUNNING);
        legacy.succeed(QUIZ_ID, 100L, QuizFixtures.NOW);
        given(generationRepository.findFirstByQuizIdOrderByGenerationIdAsc(QUIZ_ID)).willReturn(Optional.of(legacy));

        CreatorQuizViews.Revision legacyView = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L);

        assertThat(legacyView.contentEdited()).isNull();
        assertThat(legacyView.contentConfirmedAt()).isNull();

        given(generationRepository.findFirstByQuizIdOrderByGenerationIdAsc(QUIZ_ID)).willReturn(Optional.empty());
        CreatorQuizViews.Revision noGeneration = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L);

        assertThat(noGeneration.contentEdited()).isNull();
        assertThat(noGeneration.contentConfirmedAt()).isNull();
    }

    @Test
    void 같은_Quiz의_모든_revision은_같은_원문_검토_정보를_보여준다() {
        givenRevisions();
        given(generationRepository.findFirstByQuizIdOrderByGenerationIdAsc(QUIZ_ID))
                .willReturn(Optional.of(confirmed("AI 원문", "고친 원문", QuizFixtures.NOW)));

        CreatorQuizViews.Revision first = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L);
        CreatorQuizViews.Revision second = creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 101L);

        assertThat(second.latest()).isTrue();
        assertThat(second.contentEdited()).isEqualTo(first.contentEdited()).isTrue();
        assertThat(second.contentConfirmedAt()).isEqualTo(first.contentConfirmedAt()).isEqualTo(QuizFixtures.NOW);
    }

    @Test
    void 다른_Creator의_Quiz_revision은_원문_검토_정보를_조회하지_않고_403이다() {
        givenRevisions();
        ReflectionTestUtils.setField(quiz, "creatorId", 99L);

        assertError(() -> creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 100L), CommonErrorCode.FORBIDDEN);
        then(generationRepository).should(never()).findFirstByQuizIdOrderByGenerationIdAsc(anyLong());
    }

    @Test
    void 다른_Creator의_Quiz와_없는_revision을_구분한다() {
        given(revisionRepository.findByQuizIdOrderByRevisionNoAsc(QUIZ_ID))
                .willReturn(List.of(QuizFixtures.revision(100L, QUIZ_ID, 1)));
        assertError(() -> creatorQueryService.findRevision(MEMBER_ID, QUIZ_ID, 999L), QuizErrorCode.QUIZ_REVISION_NOT_FOUND);

        ReflectionTestUtils.setField(quiz, "creatorId", 99L);
        assertError(() -> creatorQueryService.findDetail(MEMBER_ID, QUIZ_ID), CommonErrorCode.FORBIDDEN);
        given(quizRepository.findById(2L)).willReturn(Optional.empty());
        assertError(() -> creatorQueryService.findDetail(MEMBER_ID, 2L), QuizErrorCode.QUIZ_NOT_FOUND);
    }

    @Test
    void 공개_상세는_게시_revision의_문항과_보기만_순서대로_보여준다() {
        quiz.publish(100L, QuizFixtures.revisionSha256(), QuizFixtures.NOW);
        given(revisionRepository.findById(100L)).willReturn(Optional.of(QuizFixtures.revision(100L, QUIZ_ID, 1)));

        PublicQuizViews.Detail detail = publicQueryService.findPublishedDetail(CREATOR_ID, QUIZ_ID);

        assertThat(detail.revisionId()).isEqualTo(100L);
        assertThat(detail.videoId()).isEqualTo(QuizFixtures.VIDEO_ID);
        assertThat(detail.questions()).extracting(PublicQuizViews.Question::questionNo).containsExactly(0, 1);
        assertThat(detail.questions().get(0).options()).containsExactly("구름", "돌");
    }

    @Test
    void 미게시_게시취소_다른_Creator의_Quiz는_공개_상세에서_404다() {
        assertError(() -> publicQueryService.findPublishedDetail(CREATOR_ID, QUIZ_ID), QuizErrorCode.QUIZ_NOT_FOUND);

        quiz.publish(100L, QuizFixtures.revisionSha256(), QuizFixtures.NOW);
        quiz.unpublish(QuizFixtures.NOW);
        assertError(() -> publicQueryService.findPublishedDetail(CREATOR_ID, QUIZ_ID), QuizErrorCode.QUIZ_NOT_FOUND);

        quiz.publish(100L, QuizFixtures.revisionSha256(), QuizFixtures.NOW);
        given(creatorRepository.existsById(11L)).willReturn(true);
        assertError(() -> publicQueryService.findPublishedDetail(11L, QUIZ_ID), QuizErrorCode.QUIZ_NOT_FOUND);
    }

    @Test
    void 없는_Creator의_공개_조회는_RESOURCE_NOT_FOUND다() {
        given(creatorRepository.existsById(12L)).willReturn(false);

        assertError(() -> publicQueryService.findPublished(12L, PageRequest.of(0, 20)), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> publicQueryService.findPublishedDetail(12L, QUIZ_ID), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 공개_목록은_게시_revision의_제목과_문항_수를_보여준다() {
        PageRequest pageable = PageRequest.of(1, 1);
        given(quizRepository.findPublishedByCreatorId(CREATOR_ID, pageable)).willReturn(new PageImpl<>(
                List.of(new PublishedQuizSummary(QUIZ_ID, 100L, QuizFixtures.NOW)), pageable, 3));
        given(revisionRepository.findAllById(List.of(100L))).willReturn(List.of(QuizFixtures.revision(100L, QUIZ_ID, 1)));

        Page<PublicQuizViews.Summary> page = publicQueryService.findPublished(CREATOR_ID, pageable);

        PublicQuizViews.Summary summary = page.getContent().get(0);
        assertThat(summary.quizId()).isEqualTo(QUIZ_ID);
        assertThat(summary.revisionId()).isEqualTo(100L);
        assertThat(summary.title()).isEqualTo(QuizFixtures.TITLE);
        assertThat(summary.questionCount()).isEqualTo(2);
        assertThat(summary.publishedAt()).isEqualTo(QuizFixtures.NOW);
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    private void givenRevisions() {
        given(revisionRepository.findByQuizIdOrderByRevisionNoAsc(QUIZ_ID)).willReturn(List.of(
                QuizFixtures.revision(100L, QUIZ_ID, 1), QuizFixtures.revision(101L, QUIZ_ID, 2)));
        given(decisionRepository.findByRevisionId(anyLong())).willReturn(Optional.empty());
        given(questionRepository.findByRevisionIdOrderByQuestionNoAsc(101L)).willReturn(QuizFixtures.questionRows(101L));
    }

    /** 원문을 확정해 Quiz를 만든 원문 검토 작업. */
    private static QuizGeneration confirmed(String aiContentText, String reviewedContentText, Instant confirmedAt) {
        QuizGeneration generation = new QuizGeneration(CREATOR_ID, MEMBER_ID, "req", QuizFixtures.VIDEO_ID, "제목", 3,
                QuizFixtures.NOW, true);
        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.RUNNING);
        ReflectionTestUtils.setField(generation, "reviewStage", QuizGenerationReviewStage.AWAITING_REVIEW);
        ReflectionTestUtils.setField(generation, "aiContentText", aiContentText);
        generation.confirmContent(reviewedContentText, "sha", MEMBER_ID, confirmedAt);
        generation.succeed(QUIZ_ID, 100L, confirmedAt);
        return generation;
    }

    private static void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}

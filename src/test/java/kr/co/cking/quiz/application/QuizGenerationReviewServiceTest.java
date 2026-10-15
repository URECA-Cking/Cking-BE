package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.grounding.VideoGroundingValidator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 원문 확정·검토 취소 Service 검증(이슈 #604). HTTP API는 BE-3에서 붙인다. */
class QuizGenerationReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final long MEMBER_ID = 7L;
    private static final long CREATOR_ID = 10L;
    private static final long ID = 3L;
    private static final String TEXT = "Creator가 확인한 원문";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizGenerationStore store = mock(QuizGenerationStore.class);
    private final QuizGenerationDispatcher dispatcher = mock(QuizGenerationDispatcher.class);
    private final QuizGenerationProperties properties = new QuizGenerationProperties();
    private QuizGenerationService service;
    private QuizGeneration generation;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        service = new QuizGenerationService(new QuizCreatorLookup(memberRepository, creatorRepository), store,
                dispatcher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator(CREATOR_ID)));
        generation = awaiting(CREATOR_ID);
        given(store.find(ID)).willReturn(Optional.of(generation));
        given(store.confirmContent(ID, MEMBER_ID, TEXT, NOW)).willReturn(QuizGenerationStore.Confirmation.CONFIRMED);
        given(dispatcher.dispatchGeneration(ID)).willReturn(true);
    }

    @Test
    void 원문을_확정하면_오래된_작업을_정리하고_확정한_뒤_생성_단계를_제출한다() {
        service.confirmContent(MEMBER_ID, ID, TEXT);

        InOrder order = inOrder(store, dispatcher);
        order.verify(store).expireStale(CREATOR_ID, NOW);
        order.verify(store).confirmContent(ID, MEMBER_ID, TEXT, NOW);
        order.verify(dispatcher).dispatchGeneration(ID);
        verify(dispatcher, never()).dispatch(anyLong());
        verify(store, never()).failUnstartedGeneration(anyLong(), any(), any());
    }

    @Test
    void 생성_단계_제출이_거절되면_시작_전_작업을_끝내_슬롯을_푼다() {
        given(dispatcher.dispatchGeneration(ID)).willReturn(false);

        service.confirmContent(MEMBER_ID, ID, TEXT);

        verify(store).failUnstartedGeneration(ID, QuizGenerationFailure.DISPATCH_REJECTED, NOW);
    }

    @Test
    void 같은_원문_재확정은_다시_제출하지_않고_생성_기능이_꺼져_있어도_현재_상태를_돌려준다() {
        generation.confirmContent(TEXT, QuizRevisionFingerprint.contentTextSha256(TEXT), MEMBER_ID, NOW);
        given(store.confirmContent(ID, MEMBER_ID, TEXT, NOW)).willReturn(QuizGenerationStore.Confirmation.REPLAYED);
        properties.setEnabled(false);

        assertThat(service.confirmContent(MEMBER_ID, ID, TEXT).generationId()).isEqualTo(ID);

        verify(store).confirmContent(ID, MEMBER_ID, TEXT, NOW);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 생성_기능이_꺼져_있으면_새_확정은_503이고_취소는_된다() {
        properties.setEnabled(false);

        assertError(() -> service.confirmContent(MEMBER_ID, ID, TEXT), QuizErrorCode.QUIZ_GENERATION_DISABLED);
        verify(store, never()).confirmContent(anyLong(), anyLong(), anyString(), any());

        given(store.cancelReview(ID, NOW)).willReturn(QuizGenerationStore.Cancellation.CANCELLED);
        service.cancelReview(MEMBER_ID, ID);
        verify(store).cancelReview(ID, NOW);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 원문은_비어_있지_않은_50000자_이하만_받는다() {
        int max = VideoGroundingValidator.MAX_CONTENT_TEXT_LENGTH;
        for (String text : new String[] {null, "", " ", "\n\t", "가".repeat(max + 1)}) {
            assertError(() -> service.confirmContent(MEMBER_ID, ID, text), CommonErrorCode.VALIDATION_FAILED);
        }
        verify(store, never()).confirmContent(anyLong(), anyLong(), anyString(), any());

        String longest = "가".repeat(max);
        given(store.confirmContent(ID, MEMBER_ID, longest, NOW)).willReturn(QuizGenerationStore.Confirmation.CONFIRMED);
        service.confirmContent(MEMBER_ID, ID, longest);
        verify(store).confirmContent(ID, MEMBER_ID, longest, NOW);
    }

    @Test
    void 원문은_정규화하지_않고_그대로_넘긴다() {
        String raw = "  앞뒤 공백과\r\n줄바꿈을 그대로 둔다  ";
        given(store.confirmContent(ID, MEMBER_ID, raw, NOW)).willReturn(QuizGenerationStore.Confirmation.CONFIRMED);

        service.confirmContent(MEMBER_ID, ID, raw);

        verify(store).confirmContent(ID, MEMBER_ID, raw, NOW);
    }

    @Test
    void 다른_Creator의_작업과_없는_작업은_확정하거나_취소할_수_없다() {
        given(store.find(ID)).willReturn(Optional.of(awaiting(99L)));
        assertError(() -> service.confirmContent(MEMBER_ID, ID, TEXT), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.cancelReview(MEMBER_ID, ID), CommonErrorCode.FORBIDDEN);

        given(store.find(ID)).willReturn(Optional.empty());
        assertError(() -> service.confirmContent(MEMBER_ID, ID, TEXT), QuizErrorCode.QUIZ_GENERATION_NOT_FOUND);
        assertError(() -> service.cancelReview(MEMBER_ID, ID), QuizErrorCode.QUIZ_GENERATION_NOT_FOUND);

        verify(store, never()).confirmContent(anyLong(), anyLong(), anyString(), any());
        verify(store, never()).cancelReview(anyLong(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void Store가_거부한_확정과_취소는_그대로_실패하고_제출하지_않는다() {
        given(store.confirmContent(ID, MEMBER_ID, TEXT, NOW))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_CONTENT_CONFLICT));
        assertError(() -> service.confirmContent(MEMBER_ID, ID, TEXT), QuizErrorCode.QUIZ_GENERATION_CONTENT_CONFLICT);

        given(store.cancelReview(ID, NOW))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW));
        assertError(() -> service.cancelReview(MEMBER_ID, ID), QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
        verifyNoInteractions(dispatcher);
    }

    private static QuizGeneration awaiting(Long creatorId) {
        QuizGeneration generation = new QuizGeneration(creatorId, MEMBER_ID, "req", "dQw4w9WgXcQ", "제목", 3, NOW, true);
        ReflectionTestUtils.setField(generation, "generationId", ID);
        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.RUNNING);
        ReflectionTestUtils.setField(generation, "reviewStage", QuizGenerationReviewStage.AWAITING_REVIEW);
        ReflectionTestUtils.setField(generation, "aiContentText", "AI 원문");
        return generation;
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

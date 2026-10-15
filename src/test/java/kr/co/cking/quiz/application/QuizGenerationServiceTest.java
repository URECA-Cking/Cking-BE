package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuizGenerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final long MEMBER_ID = 7L;
    private static final long CREATOR_ID = 10L;
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizGenerationStore store = mock(QuizGenerationStore.class);
    private final QuizGenerationDispatcher dispatcher = mock(QuizGenerationDispatcher.class);
    private final QuizGenerationProperties properties = new QuizGenerationProperties();
    private QuizGenerationService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        service = new QuizGenerationService(new QuizCreatorLookup(memberRepository, creatorRepository), store,
                dispatcher, properties, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
        given(creatorRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(creator(CREATOR_ID)));
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.empty());
        given(store.create(any(QuizGeneration.class))).willAnswer(invocation -> withId(invocation.getArgument(0), 1L));
        given(dispatcher.dispatch(1L)).willReturn(true);
    }

    @Test
    void 접수하면_오래된_작업을_정리하고_저장한_뒤_Executor에_제출한다() {
        given(store.find(1L)).willAnswer(invocation -> Optional.of(created()));

        QuizGenerationView view = service.request(MEMBER_ID, command(URL, "물의 순환", null));

        ArgumentCaptor<QuizGeneration> saved = ArgumentCaptor.forClass(QuizGeneration.class);
        InOrder order = inOrder(store, dispatcher);
        order.verify(store).expireStale(CREATOR_ID, NOW);
        order.verify(store).create(saved.capture());
        order.verify(dispatcher).dispatch(1L);
        assertThat(saved.getValue().getVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(saved.getValue().getQuestionCount()).isEqualTo(3);
        assertThat(saved.getValue().getOptionCount()).isEqualTo(4);
        assertThat(saved.getValue().getRequestedByMemberId()).isEqualTo(MEMBER_ID);
        assertThat(view.status()).isEqualTo(QuizGenerationStatus.PENDING);
    }

    @Test
    void 원문_검토_플래그를_켜면_새_작업은_Grounding_단계로_만들고_끄면_기존_흐름이다() {
        given(store.find(1L)).willAnswer(invocation -> Optional.of(created()));
        ArgumentCaptor<QuizGeneration> saved = ArgumentCaptor.forClass(QuizGeneration.class);

        service.request(MEMBER_ID, command(URL, "제목", 3));
        properties.getContentReview().setEnabled(true);
        service.request(MEMBER_ID, new QuizGenerationCommand(UUID.randomUUID(), URL, "제목", 3));

        verify(store, org.mockito.Mockito.times(2)).create(saved.capture());
        assertThat(saved.getAllValues().get(0).getReviewStage()).isNull();
        assertThat(saved.getAllValues().get(1).getReviewStage())
                .isEqualTo(kr.co.cking.quiz.domain.QuizGenerationReviewStage.GROUNDING);
        verify(dispatcher, org.mockito.Mockito.times(2)).dispatch(1L);
        verify(dispatcher, never()).dispatchGeneration(anyLong());
    }

    @Test
    void 문항_수는_3과_5만_허용한다() {
        given(store.find(1L)).willAnswer(invocation -> Optional.of(created()));
        assertThat(service.request(MEMBER_ID, command(URL, "제목", 5)).generationId()).isEqualTo(1L);

        for (int count : new int[] {0, 1, 4, 6, 10}) {
            assertError(() -> service.request(MEMBER_ID, command(URL, "제목", count)), CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    void 제목과_URL_requestId가_잘못되면_저장하지_않는다() {
        assertError(() -> service.request(MEMBER_ID, command(URL, " ", 3)), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.request(MEMBER_ID, command(URL, "가".repeat(101), 3)), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.request(MEMBER_ID, command("https://evil.com/watch?v=dQw4w9WgXcQ", "제목", 3)),
                CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.request(MEMBER_ID, new QuizGenerationCommand(null, URL, "제목", 3)),
                CommonErrorCode.VALIDATION_FAILED);
        verify(store, never()).create(any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 같은_requestId와_같은_입력은_URL_표현이_달라도_기존_작업을_반환한다() {
        QuizGeneration existing = withId(new QuizGeneration(CREATOR_ID, MEMBER_ID, REQUEST_ID.toString(),
                "dQw4w9WgXcQ", "제목", 3, NOW), 9L);
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(existing));

        QuizGenerationView view = service.request(MEMBER_ID, command("https://youtu.be/dQw4w9WgXcQ?si=x", "제목", null));

        assertThat(view.generationId()).isEqualTo(9L);
        verify(store, never()).create(any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 같은_requestId_재요청은_오래된_작업을_정리한_뒤_다시_읽은_상태를_반환한다() {
        // {재요청 시 처음 읽은 작업, 정리 뒤 다시 읽은 작업}: 기준 시간이 지난 PENDING·RUNNING과 지나지 않은 PENDING·RUNNING
        QuizGeneration[][] cases = {
                {existing(QuizGenerationStatus.PENDING),
                        existing(QuizGenerationStatus.FAILED, QuizGenerationFailure.STALE_PENDING)},
                {existing(QuizGenerationStatus.RUNNING),
                        existing(QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.STALE_RUNNING)},
                {existing(QuizGenerationStatus.PENDING), existing(QuizGenerationStatus.PENDING)},
                {existing(QuizGenerationStatus.RUNNING), existing(QuizGenerationStatus.RUNNING)}};
        for (QuizGeneration[] pair : cases) {
            QuizGeneration before = pair[0];
            QuizGeneration after = pair[1];
            String description = before.getStatus() + " → " + after.getStatus();
            clearInvocations(store);
            given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(before));
            given(store.find(9L)).willReturn(Optional.of(after));

            QuizGenerationView view = service.request(MEMBER_ID, command(URL, "제목", 3));

            assertThat(view.generationId()).as(description).isEqualTo(9L);
            assertThat(view.status()).as(description).isEqualTo(after.getStatus());
            assertThat(view.failureCode()).as(description).isEqualTo(after.getFailureCode());
            InOrder order = inOrder(store);
            order.verify(store).findByRequest(CREATOR_ID, REQUEST_ID.toString());
            order.verify(store).expireStale(CREATOR_ID, NOW);
            order.verify(store).find(9L);
            verify(store, never()).create(any());
            verify(store, never()).claim(anyLong(), any());
        }
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 같은_requestId_재요청은_이미_끝난_작업의_결과를_그대로_반환한다() {
        QuizGeneration succeeded = existing(QuizGenerationStatus.RUNNING);
        succeeded.succeed(100L, 200L, NOW);
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(succeeded));
        given(store.find(9L)).willReturn(Optional.of(succeeded));

        QuizGenerationView view = service.request(MEMBER_ID, command(URL, "제목", 3));

        assertThat(view.status()).isEqualTo(QuizGenerationStatus.SUCCEEDED);
        assertThat(view.quizId()).isEqualTo(100L);
        assertThat(view.revisionId()).isEqualTo(200L);
        verify(store, never()).create(any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 같은_requestId에_다른_입력은_409다() {
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(new QuizGeneration(
                CREATOR_ID, MEMBER_ID, REQUEST_ID.toString(), "dQw4w9WgXcQ", "제목", 3, NOW)));

        assertError(() -> service.request(MEMBER_ID, command(URL, "다른 제목", 3)),
                QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT);
        assertError(() -> service.request(MEMBER_ID, command(URL, "제목", 5)),
                QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT);
        assertError(() -> service.request(MEMBER_ID, command("https://youtu.be/aaaaaaaaaaa", "제목", 3)),
                QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT);
        verify(store, never()).expireStale(anyLong(), any());
        verify(store, never()).create(any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void 기능이_꺼져_있으면_새_요청은_503이고_기존_작업_재요청은_반환한다() {
        properties.setEnabled(false);
        assertError(() -> service.request(MEMBER_ID, command(URL, "제목", 3)), QuizErrorCode.QUIZ_GENERATION_DISABLED);
        verify(store, never()).create(any());
        verify(store, never()).expireStale(anyLong(), any());

        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.of(withId(new QuizGeneration(
                CREATOR_ID, MEMBER_ID, REQUEST_ID.toString(), "dQw4w9WgXcQ", "제목", 3, NOW), 9L)));
        assertThat(service.request(MEMBER_ID, command(URL, "제목", 3)).generationId()).isEqualTo(9L);
    }

    @Test
    void 고유_제약_충돌_뒤_같은_requestId가_보이면_재요청으로_처리하고_아니면_진행_중_409다() {
        given(store.create(any(QuizGeneration.class))).willThrow(new DataIntegrityViolationException("duplicate"));
        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.empty(),
                Optional.of(withId(new QuizGeneration(CREATOR_ID, MEMBER_ID, REQUEST_ID.toString(), "dQw4w9WgXcQ",
                        "제목", 3, NOW), 9L)));

        assertThat(service.request(MEMBER_ID, command(URL, "제목", 3)).generationId()).isEqualTo(9L);

        given(store.findByRequest(CREATOR_ID, REQUEST_ID.toString())).willReturn(Optional.empty());
        assertError(() -> service.request(MEMBER_ID, command(URL, "제목", 3)), QuizErrorCode.QUIZ_GENERATION_IN_PROGRESS);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void Executor가_거절하면_PENDING을_FAILED로_끝낸다() {
        given(dispatcher.dispatch(1L)).willReturn(false);
        given(store.find(1L)).willAnswer(invocation -> Optional.of(created()));

        service.request(MEMBER_ID, command(URL, "제목", 3));

        verify(store).failPending(1L, QuizGenerationFailure.DISPATCH_REJECTED, NOW);
    }

    @Test
    void 조회는_본인_작업만_되고_조회하면서_오래된_작업을_정리한다() {
        QuizGeneration own = created();
        given(store.find(1L)).willReturn(Optional.of(own));
        assertThat(service.find(MEMBER_ID, 1L).generationId()).isEqualTo(1L);
        verify(store).expireStale(CREATOR_ID, NOW);

        given(store.find(2L)).willReturn(Optional.of(withId(new QuizGeneration(99L, 8L, "other", "dQw4w9WgXcQ",
                "제목", 3, NOW), 2L)));
        assertError(() -> service.find(MEMBER_ID, 2L), CommonErrorCode.FORBIDDEN);
        given(store.find(3L)).willReturn(Optional.empty());
        assertError(() -> service.find(MEMBER_ID, 3L), QuizErrorCode.QUIZ_GENERATION_NOT_FOUND);
    }

    @Test
    void 없는_Member는_404이고_Creator가_아니면_403이다() {
        given(memberRepository.findById(8L)).willReturn(Optional.empty());
        assertError(() -> service.request(8L, command(URL, "제목", 3)), CommonErrorCode.RESOURCE_NOT_FOUND);
        given(memberRepository.findById(9L)).willReturn(Optional.of(mock(Member.class)));
        given(creatorRepository.findByMemberId(9L)).willReturn(Optional.empty());
        assertError(() -> service.find(9L, 1L), CommonErrorCode.FORBIDDEN);
    }

    private QuizGeneration created() {
        return withId(new QuizGeneration(CREATOR_ID, MEMBER_ID, REQUEST_ID.toString(), "dQw4w9WgXcQ", "제목", 3, NOW), 1L);
    }

    /** 같은 requestId·같은 입력으로 저장된 작업(ID 9)을 원하는 상태로 만든다. */
    private QuizGeneration existing(QuizGenerationStatus status) {
        return existing(status, null);
    }

    private QuizGeneration existing(QuizGenerationStatus status, QuizGenerationFailure failure) {
        QuizGeneration generation = withId(new QuizGeneration(CREATOR_ID, MEMBER_ID, REQUEST_ID.toString(),
                "dQw4w9WgXcQ", "제목", 3, NOW), 9L);
        ReflectionTestUtils.setField(generation, "status", status);
        ReflectionTestUtils.setField(generation, "failureCode", failure);
        return generation;
    }

    private QuizGenerationCommand command(String url, String title, Integer questionCount) {
        return new QuizGenerationCommand(REQUEST_ID, url, title, questionCount);
    }

    private static QuizGeneration withId(QuizGeneration generation, Long id) {
        ReflectionTestUtils.setField(generation, "generationId", id);
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

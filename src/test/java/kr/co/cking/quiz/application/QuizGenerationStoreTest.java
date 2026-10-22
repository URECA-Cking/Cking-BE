package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.repository.QuizGenerationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuizGenerationStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");

    private final QuizGenerationRepository repository = mock(QuizGenerationRepository.class);
    private final QuizDraftService draftService = mock(QuizDraftService.class);
    private final QuizGenerationProperties properties = new QuizGenerationProperties();
    private final QuizGenerationStore store = new QuizGenerationStore(repository, draftService, properties);
    private final QuizDraftCommand command = new QuizDraftCommand(10L, QuizGenerationFixtures.VIDEO_ID, "제목",
            new QuizGenerationInput(QuizGenerationFixtures.CONTENT_TEXT, 3, 4), QuizGenerationFixtures.generated(3));

    @Test
    void RUNNING_작업만_Quiz를_저장하고_같은_Transaction에서_SUCCEEDED가_된다() {
        QuizGeneration generation = generation(QuizGenerationStatus.RUNNING);
        given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));
        given(draftService.create(command)).willReturn(new QuizDraft(50L, 500L));

        assertThat(store.completeRunning(1L, command, NOW)).contains(new QuizDraft(50L, 500L));
        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.SUCCEEDED);
        assertThat(generation.getQuizId()).isEqualTo(50L);
        assertThat(generation.getRevisionId()).isEqualTo(500L);
    }

    @Test
    void 이미_UNKNOWN이나_FAILED로_정리된_작업은_Quiz를_저장하지_않는다() {
        for (QuizGenerationStatus status : new QuizGenerationStatus[] {
                QuizGenerationStatus.UNKNOWN, QuizGenerationStatus.FAILED, QuizGenerationStatus.PENDING,
                QuizGenerationStatus.SUCCEEDED}) {
            given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation(status)));

            assertThat(store.completeRunning(1L, command, NOW)).isEmpty();
        }
        verifyNoInteractions(draftService);
    }

    @Test
    void Quiz_저장이_실패하면_작업을_SUCCEEDED로_바꾸지_않는다() {
        QuizGeneration generation = generation(QuizGenerationStatus.RUNNING);
        given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));
        given(draftService.create(any())).willThrow(new BusinessException(QuizErrorCode.QUIZ_DRAFT_INVALID));

        assertThatThrownBy(() -> store.completeRunning(1L, command, NOW)).isInstanceOf(BusinessException.class);
        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.RUNNING);
    }

    @Test
    void 오래된_작업은_설정한_기준_시간으로_정리한다() {
        properties.setStalePending(Duration.ofMinutes(10));
        properties.setStaleRunning(Duration.ofMinutes(15));

        store.expireStale(10L, NOW);

        verify(repository).expirePending(10L, NOW.minus(Duration.ofMinutes(10)), QuizGenerationStatus.PENDING,
                QuizGenerationStatus.FAILED, QuizGenerationFailure.STALE_PENDING, NOW);
        verify(repository).expireRunning(10L, NOW.minus(Duration.ofMinutes(15)), QuizGenerationStatus.RUNNING,
                QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.STALE_RUNNING,
                NOW);
    }

    /** 검토 대기는 어떤 규칙에도 없고, 생성 단계는 확정·생성 시작 시각으로 따로 정리한다. */
    @Test
    void 생성_단계는_확정과_생성_시작_시각으로_정리하고_검토_대기는_정리하지_않는다() {
        properties.setStalePending(Duration.ofMinutes(10));
        properties.setStaleRunning(Duration.ofMinutes(15));

        store.expireStale(10L, NOW);

        verify(repository).expireUnstartedGeneration(10L, NOW.minus(Duration.ofMinutes(10)),
                QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.STALE_PENDING, NOW);
        verify(repository).expireStartedGeneration(10L, NOW.minus(Duration.ofMinutes(15)),
                QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.UNKNOWN,
                QuizGenerationFailure.STALE_RUNNING, NOW);
        verify(repository, never()).expireRunning(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(QuizGenerationReviewStage.AWAITING_REVIEW), any(), any(), any());
    }

    @Test
    void 검토_대기_작업만_원문을_확정하고_같은_원문은_멱등이며_다른_원문은_거부한다() {
        QuizGeneration generation = reviewing(QuizGenerationReviewStage.AWAITING_REVIEW);
        given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));

        assertThat(store.confirmContent(1L, 7L, "수정한 원문", NOW)).isEqualTo(QuizGenerationStore.Confirmation.CONFIRMED);
        assertThat(generation.getReviewStage()).isEqualTo(QuizGenerationReviewStage.GENERATING);
        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.RUNNING);
        assertThat(generation.getReviewedContentText()).isEqualTo("수정한 원문");
        assertThat(generation.getReviewedContentSha256())
                .isEqualTo(QuizRevisionFingerprint.contentTextSha256("수정한 원문"));
        assertThat(generation.getContentConfirmedByMemberId()).isEqualTo(7L);
        assertThat(generation.getContentConfirmedAt()).isEqualTo(NOW);

        assertThat(store.confirmContent(1L, 7L, "수정한 원문", NOW.plusSeconds(5)))
                .isEqualTo(QuizGenerationStore.Confirmation.REPLAYED);
        assertThat(generation.getContentConfirmedAt()).isEqualTo(NOW);
        assertError(() -> store.confirmContent(1L, 7L, "수정한 원문 ", NOW),
                QuizErrorCode.QUIZ_GENERATION_CONTENT_CONFLICT);
        assertThat(generation.getReviewedContentText()).isEqualTo("수정한 원문");
    }

    @Test
    void 검토_대기가_아니면_원문을_확정하지_않는다() {
        QuizGeneration grounding = reviewing(QuizGenerationReviewStage.GROUNDING);
        QuizGeneration cancelled = reviewing(QuizGenerationReviewStage.AWAITING_REVIEW);
        cancelled.cancelReview(NOW);
        QuizGeneration legacy = generation(QuizGenerationStatus.RUNNING);
        QuizGeneration expired = reviewing(QuizGenerationReviewStage.AWAITING_REVIEW);
        ReflectionTestUtils.setField(expired, "status", QuizGenerationStatus.UNKNOWN);

        for (QuizGeneration generation : new QuizGeneration[] {grounding, cancelled, legacy, expired}) {
            given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));
            assertError(() -> store.confirmContent(1L, 7L, "원문", NOW), QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
            assertThat(generation.getReviewedContentText()).isNull();
        }
    }

    @Test
    void 검토_대기_작업만_취소하고_중복_취소는_멱등이다() {
        QuizGeneration generation = reviewing(QuizGenerationReviewStage.AWAITING_REVIEW);
        given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));

        assertThat(store.cancelReview(1L, NOW)).isEqualTo(QuizGenerationStore.Cancellation.CANCELLED);
        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.FAILED);
        assertThat(generation.getFailureCode()).isEqualTo(QuizGenerationFailure.CANCELLED);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        assertThat(generation.getAiContentText()).as("AI 원문은 남긴다").isEqualTo("AI 원문");

        assertThat(store.cancelReview(1L, NOW.plusSeconds(5))).isEqualTo(QuizGenerationStore.Cancellation.REPLAYED);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        assertError(() -> store.confirmContent(1L, 7L, "원문", NOW), QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
    }

    @Test
    void 검토_대기가_아니면_취소하지_않는다() {
        QuizGeneration confirmed = reviewing(QuizGenerationReviewStage.AWAITING_REVIEW);
        confirmed.confirmContent("원문", QuizRevisionFingerprint.contentTextSha256("원문"), 7L, NOW);
        QuizGeneration grounding = reviewing(QuizGenerationReviewStage.GROUNDING);
        QuizGeneration legacy = generation(QuizGenerationStatus.RUNNING);
        QuizGeneration failed = generation(QuizGenerationStatus.FAILED);
        ReflectionTestUtils.setField(failed, "failureCode", QuizGenerationFailure.PROVIDER_ERROR);

        for (QuizGeneration generation : new QuizGeneration[] {confirmed, grounding, legacy, failed}) {
            QuizGenerationStatus before = generation.getStatus();
            given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));
            assertError(() -> store.cancelReview(1L, NOW), QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
            assertThat(generation.getStatus()).isEqualTo(before);
        }
        given(repository.findByIdForUpdate(2L)).willReturn(Optional.empty());
        assertError(() -> store.cancelReview(2L, NOW), QuizErrorCode.QUIZ_GENERATION_NOT_FOUND);
    }

    @Test
    void 원문_검토_작업은_생성을_시작한_뒤_확정_원문과_같은_입력일_때만_Quiz를_저장한다() {
        QuizGeneration generation = reviewing(QuizGenerationReviewStage.AWAITING_REVIEW);
        String reviewed = QuizGenerationFixtures.CONTENT_TEXT;
        generation.confirmContent(reviewed, QuizRevisionFingerprint.contentTextSha256(reviewed), 7L, NOW);
        given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(generation));
        given(draftService.create(command)).willReturn(new QuizDraft(50L, 500L));

        assertThat(store.completeRunning(1L, command, NOW)).as("생성 시작 전").isEmpty();
        verifyNoInteractions(draftService);

        ReflectionTestUtils.setField(generation, "generationStartedAt", NOW);
        QuizDraftCommand other = new QuizDraftCommand(10L, QuizGenerationFixtures.VIDEO_ID, "제목",
                new QuizGenerationInput(reviewed + " AI 원문", 3, 4), QuizGenerationFixtures.generated(3));
        assertThatThrownBy(() -> store.completeRunning(1L, other, NOW)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(draftService);
        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.RUNNING);

        assertThat(store.completeRunning(1L, command, NOW)).contains(new QuizDraft(50L, 500L));
        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.SUCCEEDED);
    }

    @Test
    void 원문_검토_단계_전이는_현재_status와_단계를_조건으로_건다() {
        given(repository.awaitReview(1L, QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GROUNDING,
                QuizGenerationReviewStage.AWAITING_REVIEW, "AI 원문", "[]", "model", "v1", NOW)).willReturn(1, 0);
        assertThat(store.awaitReview(1L, "AI 원문", "[]", "model", "v1", NOW)).isTrue();
        assertThat(store.awaitReview(1L, "AI 원문", "[]", "model", "v1", NOW)).isFalse();

        given(repository.claimGeneration(1L, QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GENERATING, NOW))
                .willReturn(1, 0);
        assertThat(store.claimGeneration(1L, NOW)).isTrue();
        assertThat(store.claimGeneration(1L, NOW)).isFalse();

        given(repository.failUnstartedGeneration(1L, QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GENERATING,
                QuizGenerationStatus.FAILED, QuizGenerationFailure.DISPATCH_REJECTED, NOW)).willReturn(1);
        assertThat(store.failUnstartedGeneration(1L, QuizGenerationFailure.DISPATCH_REJECTED, NOW)).isTrue();

        given(repository.finishInStage(1L, QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GROUNDING,
                QuizGenerationStatus.FAILED, QuizGenerationFailure.GROUNDING_INVALID, NOW)).willReturn(1);
        assertThat(store.finishRunning(1L, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.GROUNDING_INVALID, NOW)).isTrue();
        verify(repository, never()).finish(1L, QuizGenerationStatus.RUNNING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.GROUNDING_INVALID, NOW);
    }

    @Test
    void 상태_전이는_현재_상태를_조건으로_건다() {
        given(repository.claim(1L, QuizGenerationStatus.PENDING, QuizGenerationStatus.RUNNING, NOW)).willReturn(1, 0);
        assertThat(store.claim(1L, NOW)).isTrue();
        assertThat(store.claim(1L, NOW)).isFalse();

        given(repository.finish(1L, QuizGenerationStatus.PENDING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.DISPATCH_REJECTED, NOW)).willReturn(1);
        assertThat(store.failPending(1L, QuizGenerationFailure.DISPATCH_REJECTED, NOW)).isTrue();

        given(repository.finish(1L, QuizGenerationStatus.RUNNING, QuizGenerationStatus.UNKNOWN,
                QuizGenerationFailure.PROVIDER_TIMEOUT, NOW)).willReturn(0);
        assertThat(store.finishRunning(1L, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.PROVIDER_TIMEOUT, NOW))
                .isFalse();
    }

    /** RUNNING인 원문 검토 작업. 검토 대기 이후 단계에는 AI 원문을 채운다. */
    private QuizGeneration reviewing(QuizGenerationReviewStage stage) {
        QuizGeneration generation = new QuizGeneration(10L, 7L, "req", QuizGenerationFixtures.VIDEO_ID, "제목", 3,
                NOW.minusSeconds(60), true);
        ReflectionTestUtils.setField(generation, "generationId", 1L);
        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.RUNNING);
        ReflectionTestUtils.setField(generation, "reviewStage", stage);
        if (stage != QuizGenerationReviewStage.GROUNDING) {
            ReflectionTestUtils.setField(generation, "aiContentText", "AI 원문");
        }
        return generation;
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, QuizErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private QuizGeneration generation(QuizGenerationStatus status) {
        QuizGeneration generation = new QuizGeneration(10L, 7L, "req", QuizGenerationFixtures.VIDEO_ID, "제목", 3,
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(generation, "generationId", 1L);
        ReflectionTestUtils.setField(generation, "status", status);
        return generation;
    }
}

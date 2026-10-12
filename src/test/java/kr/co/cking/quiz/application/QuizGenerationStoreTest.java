package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
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
                QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.STALE_RUNNING, NOW);
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

    private QuizGeneration generation(QuizGenerationStatus status) {
        QuizGeneration generation = new QuizGeneration(10L, 7L, "req", QuizGenerationFixtures.VIDEO_ID, "제목", 3,
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(generation, "generationId", 1L);
        ReflectionTestUtils.setField(generation, "status", status);
        return generation;
    }
}

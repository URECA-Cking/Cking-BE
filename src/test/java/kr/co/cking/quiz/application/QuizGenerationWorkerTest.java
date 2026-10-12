package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizInputException;
import kr.co.cking.quiz.generation.QuizProviderException;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.generation.QuizResponseParseException;
import kr.co.cking.quiz.grounding.VideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounding;
import kr.co.cking.quiz.grounding.VideoGroundingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class QuizGenerationWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final long ID = 1L;
    private static final String CONTENT_TEXT = QuizGenerationFixtures.CONTENT_TEXT;

    private final QuizGenerationStore store = mock(QuizGenerationStore.class);
    private final VideoGrounder grounder = mock(VideoGrounder.class);
    private final QuizGenerator generator = mock(QuizGenerator.class);
    private final QuizGenerationWorker worker =
            new QuizGenerationWorker(store, grounder, generator, Clock.fixed(NOW, ZoneOffset.UTC));
    private final GeneratedQuiz generated = QuizGenerationFixtures.generated(5);

    @BeforeEach
    void setUp() {
        QuizGeneration generation = new QuizGeneration(10L, 7L, "req", "dQw4w9WgXcQ", "물의 순환", 5, NOW);
        ReflectionTestUtils.setField(generation, "generationId", ID);
        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.RUNNING);
        given(store.claim(ID, NOW)).willReturn(true);
        given(store.find(ID)).willReturn(Optional.of(generation));
        given(grounder.ground("dQw4w9WgXcQ"))
                .willReturn(new VideoGrounding(CONTENT_TEXT, List.of(), "quiz-grounding-v1", "model"));
        given(generator.generate(any())).willReturn(generated);
        given(store.completeRunning(anyLong(), any(), any())).willReturn(Optional.of(new QuizDraft(50L, 500L)));
    }

    @Test
    void 점유한_작업을_Grounding_생성_저장_순서로_처리한다() {
        worker.run(ID);

        ArgumentCaptor<QuizGenerationInput> input = ArgumentCaptor.forClass(QuizGenerationInput.class);
        verify(generator).generate(input.capture());
        assertThat(input.getValue()).isEqualTo(new QuizGenerationInput(CONTENT_TEXT, 5, 4));
        ArgumentCaptor<QuizDraftCommand> command = ArgumentCaptor.forClass(QuizDraftCommand.class);
        verify(store).completeRunning(org.mockito.ArgumentMatchers.eq(ID), command.capture(),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(command.getValue()).isEqualTo(new QuizDraftCommand(10L, "dQw4w9WgXcQ", "물의 순환",
                new QuizGenerationInput(CONTENT_TEXT, 5, 4), generated));
        verify(store, never()).finishRunning(anyLong(), any(), any(), any());
    }

    @Test
    void 점유하지_못하면_Provider를_호출하지_않는다() {
        given(store.claim(ID, NOW)).willReturn(false);

        worker.run(ID);

        verifyNoInteractions(grounder, generator);
        verify(store, never()).completeRunning(anyLong(), any(), any());
    }

    @Test
    void timeout은_UNKNOWN이고_다시_호출하지_않는다() {
        willThrow(new QuizProviderTimeoutException("timeout", null)).given(grounder).ground("dQw4w9WgXcQ");
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.PROVIDER_TIMEOUT, NOW);
        verify(grounder).ground("dQw4w9WgXcQ");
        verifyNoInteractions(generator);

        willReturn(new VideoGrounding(CONTENT_TEXT, List.of(), "quiz-grounding-v1", "model"))
                .given(grounder).ground("dQw4w9WgXcQ");
        willThrow(new QuizProviderTimeoutException("timeout", null)).given(generator).generate(any());
        worker.run(ID);
        verify(store, org.mockito.Mockito.times(2))
                .finishRunning(ID, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.PROVIDER_TIMEOUT, NOW);
        verify(store, never()).completeRunning(anyLong(), any(), any());
    }

    @Test
    void Provider_실패는_안전한_실패_코드로_FAILED가_된다() {
        Map<RuntimeException, QuizGenerationFailure> groundingFailures = Map.of(
                new VideoGroundingException(VideoGroundingException.Failure.REJECTED), QuizGenerationFailure.GROUNDING_REJECTED,
                new VideoGroundingException(VideoGroundingException.Failure.INVALID_RESPONSE), QuizGenerationFailure.GROUNDING_INVALID,
                new VideoGroundingException(VideoGroundingException.Failure.PROVIDER_ERROR), QuizGenerationFailure.PROVIDER_ERROR,
                new VideoGroundingException(VideoGroundingException.Failure.NOT_CONFIGURED), QuizGenerationFailure.PROVIDER_ERROR);
        groundingFailures.forEach((exception, failure) -> {
            willThrow(exception).given(grounder).ground("dQw4w9WgXcQ");
            worker.run(ID);
            verify(store).finishRunning(ID, QuizGenerationStatus.FAILED, failure, NOW);
            org.mockito.Mockito.clearInvocations(store);
        });

        willReturn(new VideoGrounding(CONTENT_TEXT, List.of(), "quiz-grounding-v1", "model"))
                .given(grounder).ground("dQw4w9WgXcQ");
        Map<RuntimeException, QuizGenerationFailure> generationFailures = Map.of(
                new QuizResponseParseException("parse"), QuizGenerationFailure.GENERATION_INVALID,
                new QuizGenerationValidationException("invalid"), QuizGenerationFailure.GENERATION_INVALID,
                new QuizInputException("input"), QuizGenerationFailure.GENERATION_INVALID,
                new QuizProviderException("provider"), QuizGenerationFailure.PROVIDER_ERROR);
        generationFailures.forEach((exception, failure) -> {
            willThrow(exception).given(generator).generate(any());
            worker.run(ID);
            verify(store).finishRunning(ID, QuizGenerationStatus.FAILED, failure, NOW);
            org.mockito.Mockito.clearInvocations(store);
        });
        verify(store, never()).completeRunning(anyLong(), any(), any());
    }

    @Test
    void 저장_검증_실패는_DRAFT_INVALID이고_그_밖의_오류는_INTERNAL_ERROR다() {
        willThrow(new BusinessException(QuizErrorCode.QUIZ_DRAFT_INVALID, "detail")).given(store).completeRunning(anyLong(), any(), any());
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationStatus.FAILED, QuizGenerationFailure.DRAFT_INVALID, NOW);

        willThrow(new QuizGenerationValidationException("x")).given(store).completeRunning(anyLong(), any(), any());
        worker.run(ID);
        verify(store, org.mockito.Mockito.times(2))
                .finishRunning(ID, QuizGenerationStatus.FAILED, QuizGenerationFailure.DRAFT_INVALID, NOW);

        willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)).given(store).completeRunning(anyLong(), any(), any());
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationStatus.FAILED, QuizGenerationFailure.INTERNAL_ERROR, NOW);
    }

    @Test
    void 이미_정리된_작업이면_실패로_바꾸지_않고_끝난다() {
        willReturn(Optional.empty()).given(store).completeRunning(anyLong(), any(), any());

        worker.run(ID);

        verify(store, never()).finishRunning(anyLong(), any(), any(), any());
    }

    @Test
    void 로그에_contentText와_Provider_원문을_남기지_않는다(CapturedOutput output) {
        willThrow(new QuizProviderException("provider-raw-91c2 " + CONTENT_TEXT)).given(generator).generate(any());
        given(store.finishRunning(anyLong(), any(), any(), any())).willReturn(true);
        worker.run(ID);
        willThrow(new IllegalStateException("unexpected-raw-91c2")).given(generator).generate(any());
        worker.run(ID);

        assertThat(output.getAll()).doesNotContain("provider-raw-91c2", "unexpected-raw-91c2", CONTENT_TEXT);
    }
}

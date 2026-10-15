package kr.co.cking.quiz.application;

import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizInputException;
import kr.co.cking.quiz.generation.QuizProviderException;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.generation.QuizResponseParseException;
import kr.co.cking.quiz.grounding.EvidenceType;
import kr.co.cking.quiz.grounding.GroundingFact;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 원문 검토 작업의 Grounding 단계({@code run})와 생성 단계({@code runGeneration}) Worker 검증(이슈 #604). */
@ExtendWith(OutputCaptureExtension.class)
class QuizGenerationReviewWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final long ID = 1L;
    private static final String AI_TEXT = QuizGenerationFixtures.CONTENT_TEXT;
    private static final String REVIEWED_TEXT = "물은 증발해 구름이 된다. 구름은 비가 되어 내린다. 비는 강으로 흐른다. "
            + "강물은 바다로 간다. 바닷물도 다시 증발한다. Creator가 고친 문장이다.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuizGenerationStore store = mock(QuizGenerationStore.class);
    private final VideoGrounder grounder = mock(VideoGrounder.class);
    private final QuizGenerator generator = mock(QuizGenerator.class);
    private final QuizGenerationWorker worker =
            new QuizGenerationWorker(store, grounder, generator, Clock.fixed(NOW, ZoneOffset.UTC), objectMapper);
    private final GeneratedQuiz generated = QuizGenerationFixtures.generated(5);
    private QuizGeneration generation;

    @BeforeEach
    void setUp() {
        generation = new QuizGeneration(10L, 7L, "req", QuizGenerationFixtures.VIDEO_ID, "물의 순환", 5, NOW, true);
        ReflectionTestUtils.setField(generation, "generationId", ID);
        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.RUNNING);
        given(store.claim(ID, NOW)).willReturn(true);
        given(store.find(ID)).willReturn(Optional.of(generation));
        given(store.awaitReview(eq(ID), anyString(), anyString(), anyString(), anyString(), eq(NOW))).willReturn(true);
        given(store.claimGeneration(ID, NOW)).willReturn(true);
        given(generator.generate(any())).willReturn(generated);
        given(store.completeRunning(anyLong(), any(), any())).willReturn(Optional.of(new QuizDraft(50L, 500L)));
    }

    @Test
    void Grounding_단계는_AI_원문과_facts를_저장하고_Quiz를_생성하지_않는다() throws Exception {
        List<GroundingFact> facts = List.of(new GroundingFact("물은 증발한다.", EvidenceType.SPEECH, "물이 증발합니다",
                "00:10", "00:12.5"));
        given(grounder.ground(QuizGenerationFixtures.VIDEO_ID))
                .willReturn(new VideoGrounding(AI_TEXT, facts, "quiz-grounding-v1", "gemini-test"));

        worker.run(ID);

        ArgumentCaptor<String> factsJson = ArgumentCaptor.forClass(String.class);
        verify(store).awaitReview(eq(ID), eq(AI_TEXT), factsJson.capture(), eq("gemini-test"),
                eq("quiz-grounding-v1"), eq(NOW));
        assertThat(objectMapper.readTree(factsJson.getValue()).get(0).path("evidenceType").asString())
                .isEqualTo("SPEECH");
        assertThat(objectMapper.readTree(factsJson.getValue()).get(0).path("timestampEnd").asString())
                .isEqualTo("00:12.5");
        verifyNoInteractions(generator);
        verify(store, never()).completeRunning(anyLong(), any(), any());
        verify(store, never()).finishRunning(anyLong(), any(), any(), any(), any());
    }

    @Test
    void facts는_200개까지_저장하고_넘으면_GROUNDING_INVALID다() {
        given(grounder.ground(QuizGenerationFixtures.VIDEO_ID)).willReturn(grounding(facts(200, "근거")));
        worker.run(ID);
        verify(store).awaitReview(eq(ID), eq(AI_TEXT), anyString(), anyString(), anyString(), eq(NOW));

        given(grounder.ground(QuizGenerationFixtures.VIDEO_ID)).willReturn(grounding(facts(201, "근거")));
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.GROUNDING_INVALID, NOW);
        verify(store, times(1)).awaitReview(eq(ID), anyString(), anyString(), anyString(), anyString(), eq(NOW));
    }

    @Test
    void facts_직렬화가_512KB를_넘으면_GROUNDING_INVALID다() {
        int base = objectMapper.writeValueAsString(facts(1, "")).getBytes(StandardCharsets.UTF_8).length;
        int limit = QuizGenerationWorker.MAX_GROUNDING_FACTS_BYTES;
        given(grounder.ground(QuizGenerationFixtures.VIDEO_ID)).willReturn(grounding(facts(1, "e".repeat(limit - base))));
        worker.run(ID);
        verify(store).awaitReview(eq(ID), eq(AI_TEXT), anyString(), anyString(), anyString(), eq(NOW));

        given(grounder.ground(QuizGenerationFixtures.VIDEO_ID))
                .willReturn(grounding(facts(1, "e".repeat(limit - base + 1))));
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.GROUNDING_INVALID, NOW);
        verify(store, times(1)).awaitReview(eq(ID), anyString(), anyString(), anyString(), anyString(), eq(NOW));
    }

    @Test
    void Grounding_단계_실패는_자기_단계일_때만_끝낸다() {
        Map<RuntimeException, QuizGenerationFailure> failures = Map.of(
                new VideoGroundingException(VideoGroundingException.Failure.REJECTED), QuizGenerationFailure.GROUNDING_REJECTED,
                new VideoGroundingException(VideoGroundingException.Failure.INVALID_RESPONSE), QuizGenerationFailure.GROUNDING_INVALID,
                new VideoGroundingException(VideoGroundingException.Failure.PROVIDER_ERROR), QuizGenerationFailure.PROVIDER_ERROR,
                new QuizProviderException("provider"), QuizGenerationFailure.PROVIDER_ERROR);
        failures.forEach((exception, failure) -> {
            willThrow(exception).given(grounder).ground(QuizGenerationFixtures.VIDEO_ID);
            worker.run(ID);
            verify(store).finishRunning(ID, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED, failure,
                    NOW);
            org.mockito.Mockito.clearInvocations(store);
        });

        willThrow(new QuizProviderTimeoutException("timeout", null)).given(grounder).ground(QuizGenerationFixtures.VIDEO_ID);
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.UNKNOWN,
                QuizGenerationFailure.PROVIDER_TIMEOUT, NOW);

        willThrow(new IllegalStateException("unexpected")).given(grounder).ground(QuizGenerationFixtures.VIDEO_ID);
        worker.run(ID);
        verify(store).finishRunning(ID, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.INTERNAL_ERROR, NOW);
        verify(store, never()).finishRunning(anyLong(), any(QuizGenerationStatus.class), any(), any());
        verify(store, never()).awaitReview(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(generator);
    }

    @Test
    void 생성_단계는_확정_원문으로만_Quiz를_만든다() {
        confirm();

        worker.runGeneration(ID);

        ArgumentCaptor<QuizGenerationInput> input = ArgumentCaptor.forClass(QuizGenerationInput.class);
        verify(generator).generate(input.capture());
        assertThat(input.getValue()).isEqualTo(new QuizGenerationInput(REVIEWED_TEXT, 5, 4));
        ArgumentCaptor<QuizDraftCommand> command = ArgumentCaptor.forClass(QuizDraftCommand.class);
        verify(store).completeRunning(eq(ID), command.capture(), eq(NOW));
        assertThat(command.getValue().input().contentText()).isEqualTo(REVIEWED_TEXT).isNotEqualTo(AI_TEXT);
        verifyNoInteractions(grounder);
        verify(store, never()).claim(anyLong(), any());
    }

    @Test
    void 생성_시작을_점유하지_못하면_Provider를_호출하지_않는다() {
        confirm();
        given(store.claimGeneration(ID, NOW)).willReturn(true, false);

        worker.runGeneration(ID);
        worker.runGeneration(ID);

        verify(generator, times(1)).generate(any());
        verify(store, times(1)).completeRunning(anyLong(), any(), any());
        verifyNoInteractions(grounder);
    }

    @Test
    void 정리된_뒤_늦게_실행된_생성_단계는_아무것도_하지_않는다() {
        given(store.claimGeneration(ID, NOW)).willReturn(false);

        worker.runGeneration(ID);

        verifyNoInteractions(generator, grounder);
        verify(store, never()).find(anyLong());
        verify(store, never()).completeRunning(anyLong(), any(), any());
        verify(store, never()).finishRunning(anyLong(), any(), any(), any(), any());
    }

    @Test
    void 생성_단계_실패는_자기_단계일_때만_끝낸다() {
        confirm();
        Map<RuntimeException, QuizGenerationFailure> failures = Map.of(
                new QuizResponseParseException("parse"), QuizGenerationFailure.GENERATION_INVALID,
                new QuizGenerationValidationException("invalid"), QuizGenerationFailure.GENERATION_INVALID,
                new QuizInputException("input"), QuizGenerationFailure.GENERATION_INVALID,
                new QuizProviderException("provider"), QuizGenerationFailure.PROVIDER_ERROR);
        failures.forEach((exception, failure) -> {
            willThrow(exception).given(generator).generate(any());
            worker.runGeneration(ID);
            verify(store).finishRunning(ID, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED, failure,
                    NOW);
            org.mockito.Mockito.clearInvocations(store);
        });

        willThrow(new QuizProviderTimeoutException("timeout", null)).given(generator).generate(any());
        worker.runGeneration(ID);
        verify(store).finishRunning(ID, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.UNKNOWN,
                QuizGenerationFailure.PROVIDER_TIMEOUT, NOW);
        verify(store, never()).completeRunning(anyLong(), any(), any());
        verify(store, never()).finishRunning(anyLong(), any(QuizGenerationStatus.class), any(), any());
    }

    @Test
    void 확정_원문과_생성_입력의_해시가_다르면_저장하지_않고_INTERNAL_ERROR다(CapturedOutput output) {
        confirm();
        willThrow(new IllegalStateException("생성 입력이 확정 원문과 다릅니다. " + REVIEWED_TEXT))
                .given(store).completeRunning(anyLong(), any(), any());

        worker.runGeneration(ID);

        verify(store).finishRunning(ID, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED,
                QuizGenerationFailure.INTERNAL_ERROR, NOW);
        assertThat(output.getAll()).doesNotContain(REVIEWED_TEXT, AI_TEXT);
    }

    private void confirm() {
        ReflectionTestUtils.setField(generation, "reviewStage", QuizGenerationReviewStage.AWAITING_REVIEW);
        ReflectionTestUtils.setField(generation, "aiContentText", AI_TEXT);
        generation.confirmContent(REVIEWED_TEXT, QuizRevisionFingerprint.contentTextSha256(REVIEWED_TEXT), 7L, NOW);
        ReflectionTestUtils.setField(generation, "generationStartedAt", NOW);
    }

    private VideoGrounding grounding(List<GroundingFact> facts) {
        return new VideoGrounding(AI_TEXT, facts, "quiz-grounding-v1", "gemini-test");
    }

    private static List<GroundingFact> facts(int count, String evidence) {
        List<GroundingFact> facts = new ArrayList<>(Collections.nCopies(count,
                new GroundingFact("사실", EvidenceType.VISUAL, evidence, null, null)));
        return List.copyOf(facts);
    }
}

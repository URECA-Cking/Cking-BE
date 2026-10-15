package kr.co.cking.quiz.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.QuizGenerationService;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.application.dto.QuizGenerationCurrentView;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.grounding.EvidenceType;
import kr.co.cking.quiz.grounding.GroundingFact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorQuizGenerationController.class)
class CreatorQuizGenerationControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BODY = """
            {"requestId": "%s", "youtubeUrl": "https://youtu.be/dQw4w9WgXcQ", "title": "물의 순환", "questionCount": 5}
            """.formatted(REQUEST_ID);
    private static final String CONFIRM_BODY = "{\"contentText\": \"Creator가 확인한 원문\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizGenerationService generationService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 생성_요청은_202와_작업_상태를_반환한다() throws Exception {
        given(generationService.request(7L, new QuizGenerationCommand(UUID.fromString(REQUEST_ID),
                "https://youtu.be/dQw4w9WgXcQ", "물의 순환", 5))).willReturn(view(QuizGenerationStatus.PENDING, null, null));

        mockMvc.perform(post("/api/creator/quizzes/generations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.generationId").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.optionCount").value(4));
    }

    @Test
    void JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/creator/quizzes/generations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/creator/quizzes/generations/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/creator/quizzes/generations/current")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/creator/quizzes/generations/1/content-confirmation")
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/creator/quizzes/generations/1/cancel")).andExpect(status().isUnauthorized());
        then(generationService).should(never()).request(anyLong(), any());
        then(generationService).should(never()).findCurrent(anyLong());
        then(generationService).should(never()).confirmContent(anyLong(), anyLong(), any());
        then(generationService).should(never()).cancelReview(anyLong(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void requestId_URL_제목이_없거나_형식이_틀리면_400이다() throws Exception {
        String[] bodies = {
                "{\"youtubeUrl\": \"https://youtu.be/dQw4w9WgXcQ\", \"title\": \"제목\"}",
                "{\"requestId\": \"not-uuid\", \"youtubeUrl\": \"https://youtu.be/dQw4w9WgXcQ\", \"title\": \"제목\"}",
                "{\"requestId\": \"" + REQUEST_ID + "\", \"title\": \"제목\"}",
                "{\"requestId\": \"" + REQUEST_ID + "\", \"youtubeUrl\": \"https://youtu.be/dQw4w9WgXcQ\", \"title\": \" \"}",
                "{\"requestId\": \"" + REQUEST_ID + "\", \"youtubeUrl\": \"https://youtu.be/dQw4w9WgXcQ\", \"title\": \""
                        + "가".repeat(101) + "\"}"
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/creator/quizzes/generations").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        then(generationService).should(never()).request(anyLong(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 서비스_오류를_응답_코드로_바꾼다() throws Exception {
        given(generationService.request(anyLong(), any()))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_DISABLED))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_IN_PROGRESS))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT));

        mockMvc.perform(post("/api/creator/quizzes/generations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_DISABLED"));
        mockMvc.perform(post("/api/creator/quizzes/generations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_IN_PROGRESS"));
        mockMvc.perform(post("/api/creator/quizzes/generations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_REQUEST_CONFLICT"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 조회는_결과_식별자와_실패_코드만_반환한다() throws Exception {
        given(generationService.find(7L, 1L)).willReturn(view(QuizGenerationStatus.SUCCEEDED, 50L, null));
        given(generationService.find(7L, 2L)).willReturn(view(QuizGenerationStatus.UNKNOWN, null,
                QuizGenerationFailure.PROVIDER_TIMEOUT));

        String body = mockMvc.perform(get("/api/creator/quizzes/generations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizId").value(50))
                .andExpect(jsonPath("$.data.revisionId").value(500))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/creator/quizzes/generations/2"))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.failureCode").value("PROVIDER_TIMEOUT"));

        JsonNode data = new ObjectMapper().readTree(body).path("data");
        assertThat(data.properties().stream().map(java.util.Map.Entry::getKey).toList()).doesNotContain("contentText", "facts", "questions", "requestId",
                "requestFingerprint");
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 다른_Creator의_작업은_403이고_없는_작업은_404다() throws Exception {
        given(generationService.find(7L, 1L)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        given(generationService.find(7L, 2L)).willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_FOUND));

        mockMvc.perform(get("/api/creator/quizzes/generations/1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/creator/quizzes/generations/2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_NOT_FOUND"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 기존_흐름_작업_조회는_원문_검토_필드가_null이고_원문을_담지_않는다() throws Exception {
        given(generationService.find(7L, 1L)).willReturn(view(QuizGenerationStatus.RUNNING, null, null));

        String body = mockMvc.perform(get("/api/creator/quizzes/generations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.reviewStage").isEmpty())
                .andExpect(jsonPath("$.data.contentEdited").isEmpty())
                .andExpect(jsonPath("$.data.contentConfirmedAt").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldNames(body)).containsExactly("generationId", "status", "videoId", "title", "questionCount",
                "optionCount", "quizId", "revisionId", "failureCode", "requestedAt", "startedAt", "completedAt",
                "reviewStage", "contentEdited", "contentConfirmedAt");
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 진행_중_작업_조회는_검토_대기면_원문과_facts를_반환한다() throws Exception {
        given(generationService.findCurrent(7L)).willReturn(Optional.of(new QuizGenerationCurrentView(
                reviewView(QuizGenerationReviewStage.AWAITING_REVIEW, null, null),
                new QuizGenerationCurrentView.Content("AI 원문", List.of(
                        new GroundingFact("물은 증발한다.", EvidenceType.SPEECH, "물이 증발합니다", "00:01", "00:03"),
                        new GroundingFact("비가 온다.", EvidenceType.VISUAL, "비 장면", null, null)),
                        "gemini-test", "quiz-grounding-v1", 50000))));

        mockMvc.perform(get("/api/creator/quizzes/generations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationId").value(1))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.reviewStage").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.data.contentEdited").isEmpty())
                .andExpect(jsonPath("$.data.content.aiContentText").value("AI 원문"))
                .andExpect(jsonPath("$.data.content.facts.length()").value(2))
                .andExpect(jsonPath("$.data.content.facts[0].evidenceType").value("SPEECH"))
                .andExpect(jsonPath("$.data.content.facts[0].timestampEnd").value("00:03"))
                .andExpect(jsonPath("$.data.content.facts[1].timestampStart").isEmpty())
                .andExpect(jsonPath("$.data.content.groundingModel").value("gemini-test"))
                .andExpect(jsonPath("$.data.content.groundingPromptVersion").value("quiz-grounding-v1"))
                .andExpect(jsonPath("$.data.content.maxLength").value(50000));
        then(generationService).should(never()).find(anyLong(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 진행_중_작업이_없으면_data를_비우고_검토_대기가_아니면_content가_null이다() throws Exception {
        given(generationService.findCurrent(7L)).willReturn(Optional.empty())
                .willReturn(Optional.of(new QuizGenerationCurrentView(
                        reviewView(QuizGenerationReviewStage.GENERATING, true, NOW), null)));

        mockMvc.perform(get("/api/creator/quizzes/generations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get("/api/creator/quizzes/generations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStage").value("GENERATING"))
                .andExpect(jsonPath("$.data.contentEdited").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 원문_확정은_정규화하지_않은_원문을_넘기고_202와_작업_상태를_반환한다() throws Exception {
        String raw = "  앞뒤 공백과\n줄바꿈을 그대로 둔다  ";
        given(generationService.confirmContent(7L, 1L, raw))
                .willReturn(reviewView(QuizGenerationReviewStage.GENERATING, true, NOW));

        String body = mockMvc.perform(post("/api/creator/quizzes/generations/1/content-confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(java.util.Map.of("contentText", raw))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.reviewStage").value("GENERATING"))
                .andExpect(jsonPath("$.data.contentEdited").value(true))
                .andExpect(jsonPath("$.data.contentConfirmedAt").value("2026-10-12T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldNames(body)).doesNotContain("content", "aiContentText", "contentText", "facts");
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 원문은_비어_있지_않은_50000자_이하여야_하고_ID는_양수여야_한다() throws Exception {
        String[] bodies = {"{}", "{\"contentText\": null}", "{\"contentText\": \"\"}", "{\"contentText\": \"  \"}",
                "{\"contentText\": \"" + "가".repeat(50001) + "\"}", "{\"contentText\": "};
        for (String body : bodies) {
            mockMvc.perform(post("/api/creator/quizzes/generations/1/content-confirmation")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(post("/api/creator/quizzes/generations/0/content-confirmation")
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/creator/quizzes/generations/0/cancel")).andExpect(status().isBadRequest());
        then(generationService).should(never()).confirmContent(anyLong(), anyLong(), any());
        then(generationService).should(never()).cancelReview(anyLong(), anyLong());

        String longest = "가".repeat(50000);
        given(generationService.confirmContent(7L, 1L, longest))
                .willReturn(reviewView(QuizGenerationReviewStage.GENERATING, true, NOW));
        mockMvc.perform(post("/api/creator/quizzes/generations/1/content-confirmation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentText\": \"" + longest + "\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 원문_확정_오류를_응답_코드로_바꾼다() throws Exception {
        given(generationService.confirmContent(anyLong(), anyLong(), any()))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_CONTENT_CONFLICT))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_DISABLED))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_FOUND));
        Object[][] expected = {
                {409, "QUIZ_GENERATION_CONTENT_CONFLICT"}, {409, "QUIZ_GENERATION_NOT_AWAITING_REVIEW"},
                {503, "QUIZ_GENERATION_DISABLED"}, {403, "FORBIDDEN"}, {404, "QUIZ_GENERATION_NOT_FOUND"}};

        for (Object[] result : expected) {
            mockMvc.perform(post("/api/creator/quizzes/generations/1/content-confirmation")
                            .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                    .andExpect(status().is((int) result[0]))
                    .andExpect(jsonPath("$.code").value(result[1]));
        }
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 검토_취소는_200과_작업_상태를_반환하고_오류를_응답_코드로_바꾼다() throws Exception {
        QuizGenerationView cancelled = new QuizGenerationView(1L, QuizGenerationStatus.FAILED, "dQw4w9WgXcQ", "물의 순환",
                5, 4, null, null, QuizGenerationFailure.CANCELLED, NOW, NOW, NOW,
                QuizGenerationReviewStage.AWAITING_REVIEW, null, null);
        given(generationService.cancelReview(7L, 1L)).willReturn(cancelled)
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_FOUND));

        mockMvc.perform(post("/api/creator/quizzes/generations/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("CANCELLED"))
                .andExpect(jsonPath("$.data.reviewStage").value("AWAITING_REVIEW"));
        mockMvc.perform(post("/api/creator/quizzes/generations/1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_NOT_AWAITING_REVIEW"));
        mockMvc.perform(post("/api/creator/quizzes/generations/1/cancel")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/creator/quizzes/generations/1/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_GENERATION_NOT_FOUND"));
    }

    private static List<String> fieldNames(String body) {
        return new ObjectMapper().readTree(body).path("data").properties().stream()
                .map(java.util.Map.Entry::getKey).toList();
    }

    private QuizGenerationView reviewView(QuizGenerationReviewStage stage, Boolean edited, Instant confirmedAt) {
        return new QuizGenerationView(1L, QuizGenerationStatus.RUNNING, "dQw4w9WgXcQ", "물의 순환", 5, 4, null, null,
                null, NOW, NOW, null, stage, edited, confirmedAt);
    }

    private QuizGenerationView view(QuizGenerationStatus status, Long quizId, QuizGenerationFailure failure) {
        return new QuizGenerationView(1L, status, "dQw4w9WgXcQ", "물의 순환", 5, 4, quizId,
                quizId == null ? null : 500L, failure, NOW, null, null);
    }
}

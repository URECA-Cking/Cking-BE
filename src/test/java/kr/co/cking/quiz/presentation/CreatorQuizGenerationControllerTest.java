package kr.co.cking.quiz.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.QuizGenerationService;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
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
        then(generationService).should(never()).request(anyLong(), any());
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

    private QuizGenerationView view(QuizGenerationStatus status, Long quizId, QuizGenerationFailure failure) {
        return new QuizGenerationView(1L, status, "dQw4w9WgXcQ", "물의 순환", 5, 4, quizId,
                quizId == null ? null : 500L, failure, NOW, null, null);
    }
}

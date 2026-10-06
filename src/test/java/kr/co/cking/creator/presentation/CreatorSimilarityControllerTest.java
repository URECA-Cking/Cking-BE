package kr.co.cking.creator.presentation;

import kr.co.cking.common.security.RecommendationWriteAuthorizer;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.creator.application.CreatorSimilarityQueryService;
import kr.co.cking.creator.application.CreatorSimilarityResultService;
import kr.co.cking.creator.application.dto.CreatorSimilarityView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorSimilarityController.class)
class CreatorSimilarityControllerTest {

    private static final String INPUT_HASH = "a".repeat(64);

    @Autowired MockMvc mockMvc;
    @MockitoBean CreatorSimilarityResultService resultService;
    @MockitoBean CreatorSimilarityQueryService queryService;
    @MockitoBean RecommendationWriteAuthorizer writeAuthorizer;

    @Test
    @WithMockJwt(memberId = "7")
    void 관리자는_LLM_후보_묶음을_그대로_적재한다() throws Exception {
        given(resultService.replace(eq(10L), any()))
                .willReturn(new CreatorSimilarityResultService.StoreResult(10L, 100L, INPUT_HASH, 1, true));

        mockMvc.perform(put("/api/admin/creators/10/similar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.creatorId").value(10))
                .andExpect(jsonPath("$.data.generationId").value(100))
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.applied").value(true));

        then(writeAuthorizer).should().requireWriteAccess(any());
        then(resultService).should().replace(eq(10L), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 잘못된_inputHash는_서비스를_호출하기_전에_400으로_거부한다() throws Exception {
        mockMvc.perform(put("/api/admin/creators/10/similar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload().replace(INPUT_HASH, "old-hash")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 생성_메타데이터가_있는_빈_후보_묶음을_적재한다() throws Exception {
        given(resultService.replace(eq(10L), any()))
                .willReturn(new CreatorSimilarityResultService.StoreResult(10L, 101L, INPUT_HASH, 0, true));

        mockMvc.perform(put("/api/admin/creators/10/similar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "creatorId": 10,
                                  "method": "M4",
                                  "modelVersion": "model-v1",
                                  "inputHash": "%s",
                                  "candidates": []
                                }
                                """.formatted(INPUT_HASH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(0))
                .andExpect(jsonPath("$.data.applied").value(true));

        then(resultService).should().replace(eq(10L), argThat(command ->
                command.creatorId() == 10L
                        && command.method().equals("M4")
                        && command.modelVersion().equals("model-v1")
                        && command.inputHash().equals(INPUT_HASH)
                        && command.candidates().isEmpty()));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void null_후보는_서비스_호출_전에_400으로_거부한다() throws Exception {
        mockMvc.perform(put("/api/admin/creators/10/similar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "creatorId": 10,
                                  "candidates": [null]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void 공개_조회는_기본_size_5로_저장된_결과를_반환한다() throws Exception {
        given(queryService.findSimilar(10L, 5)).willReturn(new CreatorSimilarityView(
                10L, "M4", "model-v1", INPUT_HASH, Instant.parse("2026-10-02T00:00:00Z"),
                List.of(new CreatorSimilarityView.Candidate(
                        20L, "추천 크리에이터", new BigDecimal("1.08341234"), 1))));

        mockMvc.perform(get("/api/creators/10/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorId").value(10))
                .andExpect(jsonPath("$.data.method").value("M4"))
                .andExpect(jsonPath("$.data.candidates[0].similarCreatorId").value(20))
                .andExpect(jsonPath("$.data.candidates[0].name").value("추천 크리에이터"))
                .andExpect(jsonPath("$.data.candidates[0].rank").value(1));

        then(queryService).should().findSimilar(10L, 5);
    }

    @Test
    void 추천_결과가_없으면_빈_목록을_반환한다() throws Exception {
        given(queryService.findSimilar(10L, 5)).willReturn(CreatorSimilarityView.empty(10L));

        mockMvc.perform(get("/api/creators/10/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorId").value(10))
                .andExpect(jsonPath("$.data.candidates").isEmpty())
                .andExpect(jsonPath("$.data.method").doesNotExist());
    }

    @Test
    void 공개_조회_size는_1부터_20까지다() throws Exception {
        mockMvc.perform(get("/api/creators/10/similar").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/creators/10/similar").param("size", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(queryService).should(never()).findSimilar(any(), anyInt());
    }

    private String validPayload() {
        return """
                {
                  "creatorId": 10,
                  "candidates": [{
                    "creatorId": 10,
                    "similarCreatorId": 20,
                    "score": 1.08341234,
                    "rank": 1,
                    "method": "M4",
                    "modelVersion": "model-v1",
                    "inputHash": "%s"
                  }]
                }
                """.formatted(INPUT_HASH);
    }
}

package kr.co.cking.interest.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.security.RecommendationWriteAuthorizer;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.interest.application.InterestRecommendationResultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(InterestRecommendationController.class)
class InterestRecommendationControllerTest {

    private static final String HASH = "a".repeat(64);

    @Autowired MockMvc mockMvc;
    @MockitoBean InterestRecommendationResultService resultService;
    @MockitoBean RecommendationWriteAuthorizer writeAuthorizer;

    @Test
    @WithMockJwt(memberId = "7")
    void 후보_묶음을_그대로_적재하고_적재_결과를_반환한다() throws Exception {
        given(resultService.replace(eq("SPORTS"), any())).willReturn(
                new InterestRecommendationResultService.StoreResult("v0.2", "SPORTS", 100L, HASH, 1, true));

        mockMvc.perform(replace("SPORTS", payload("0.83451234", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.taxonomyVersion").value("v0.2"))
                .andExpect(jsonPath("$.data.interestCode").value("SPORTS"))
                .andExpect(jsonPath("$.data.generationId").value(100))
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.applied").value(true));

        then(writeAuthorizer).should().requireWriteAccess(any());
        then(resultService).should().replace(eq("SPORTS"), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 형식이_잘못된_요청은_서비스_호출_전에_400으로_거부한다() throws Exception {
        String valid = payload("0.83451234", 1);
        String[] invalid = {
                valid.replace("\"taxonomyHash\":\"" + HASH + "\"", "\"taxonomyHash\":\"" + "A".repeat(64) + "\""),
                valid.replace("\"taxonomyVersion\":\"v0.2\",", ""),
                valid.replace("\"score\":0.83451234", "\"score\":2.5"),
                valid.replace("\"score\":0.83451234", "\"score\":0.123456789"),
                valid.replace("\"rank\":1", "\"rank\":0"),
                valid.replace("\"creatorId\":20", "\"creatorId\":-1"),
                valid.replace("\"candidates\":[", "\"candidates\":[null,"),
                valid.replace("\"modelVersion\":\"model-v1\",\"inputHash\":\"" + HASH + "\",\"candidates\"",
                        "\"modelVersion\":\"\",\"inputHash\":\"" + HASH + "\",\"candidates\""),
        };
        for (String body : invalid) {
            mockMvc.perform(replace("SPORTS", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        then(resultService).should(never()).replace(any(), any());
    }

    private MockHttpServletRequestBuilder replace(String code, String body) {
        return put("/api/admin/interests/" + code + "/recommendations")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String payload(String score, int rank) {
        return """
                {"taxonomyVersion":"v0.2","taxonomyHash":"%1$s","interestCode":"SPORTS","method":"INTEREST_M3_V1",
                 "modelVersion":"model-v1","inputHash":"%1$s","candidates":[
                  {"interestCode":"SPORTS","creatorId":20,"score":%2$s,"rank":%3$d,"method":"INTEREST_M3_V1",
                   "modelVersion":"model-v1","inputHash":"%1$s"}]}
                """.formatted(HASH, score, rank).replace("\n", "").replace("                 ", "");
    }
}

package kr.co.cking.interest.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.interest.application.MemberInterestService;
import kr.co.cking.interest.application.dto.MemberInterests;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(MemberInterestController.class)
class MemberInterestControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean MemberInterestService service;

    @Test
    @WithMockJwt(memberId = "7")
    void 내_관심_분야를_JWT_회원_기준으로_조회한다() throws Exception {
        given(service.findMine(7L)).willReturn(new MemberInterests("v0.2", List.of("FITNESS", "TRAVEL")));

        mockMvc.perform(get("/api/me/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.taxonomyVersion").value("v0.2"))
                .andExpect(jsonPath("$.data.interestCodes[0]").value("FITNESS"))
                .andExpect(jsonPath("$.data.interestCodes[1]").value("TRAVEL"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 관심_분야를_저장하면_저장된_선택을_반환한다() throws Exception {
        given(service.replace(7L, "v0.2", List.of("FITNESS", "TRAVEL")))
                .willReturn(new MemberInterests("v0.2", List.of("FITNESS", "TRAVEL")));

        mockMvc.perform(replace("{\"taxonomyVersion\":\"v0.2\",\"interestCodes\":[\"FITNESS\",\"TRAVEL\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestCodes[1]").value("TRAVEL"));

        then(service).should().replace(eq(7L), eq("v0.2"), eq(List.of("FITNESS", "TRAVEL")));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 빈_배열은_전체_해제로_서비스에_전달한다() throws Exception {
        given(service.replace(7L, "v0.2", List.of())).willReturn(new MemberInterests("v0.2", List.of()));

        mockMvc.perform(replace("{\"taxonomyVersion\":\"v0.2\",\"interestCodes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestCodes").isEmpty());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 형식이_잘못된_요청은_서비스_호출_전에_400으로_거부한다() throws Exception {
        String[] invalid = {
                "{\"taxonomyVersion\":\"v0.2\",\"interestCodes\":[\"A\",\"B\",\"C\",\"D\"]}",
                "{\"taxonomyVersion\":\"v0.2\",\"interestCodes\":[\"A\",null]}",
                "{\"taxonomyVersion\":\"v0.2\",\"interestCodes\":[\"A\",\" \"]}",
                "{\"taxonomyVersion\":\"v0.2\",\"interestCodes\":null}",
                "{\"taxonomyVersion\":\"v0.2\"}",
                "{\"taxonomyVersion\":\"\",\"interestCodes\":[\"A\"]}",
                "{\"interestCodes\":[\"A\"]}",
        };
        for (String body : invalid) {
            mockMvc.perform(replace(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        then(service).should(never()).replace(any(), any(), any());
    }

    private MockHttpServletRequestBuilder replace(String body) {
        return put("/api/me/interests").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}

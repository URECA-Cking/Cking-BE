package kr.co.cking.common.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.auth.presentation.OAuth2LoginFailureHandler;
import kr.co.cking.auth.presentation.OAuth2LoginSuccessHandler;
import kr.co.cking.common.config.CorsConfig;
import kr.co.cking.common.config.JwtConfig;
import kr.co.cking.common.config.SecurityConfig;
import kr.co.cking.creator.application.CreatorSimilarityQueryService;
import kr.co.cking.creator.application.CreatorSimilarityResultService;
import kr.co.cking.creator.presentation.CreatorSimilarityController;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 운영 기본값처럼 키 해시를 설정하지 않으면 어떤 키도 인증되지 않는지 검증한다. */
@WebMvcTest(controllers = CreatorSimilarityController.class)
@Import({
        SecurityConfig.class,
        JwtConfig.class,
        JwtAuthenticationConverterConfig.class,
        CorsConfig.class,
        AccessTokenJwtValidator.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        RecommendationWriteAuthorizer.class
})
@TestPropertySource(properties = {
        "cking.cors.allowed-origins=https://frontend.cking.co.kr",
        "cking.cors.allow-credentials=false",
        "cking.auth.jwt.secret=2YNYNyIIJTSCD8zOXH/RpPp/Nm5+/n9gyVQpF5uRXlA="
})
class RecommendationApiKeyDisabledSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CreatorSimilarityResultService resultService;
    @MockitoBean CreatorSimilarityQueryService queryService;
    @MockitoBean MemberRepository memberRepository;
    @MockitoBean OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;
    @MockitoBean OAuth2LoginFailureHandler oauth2LoginFailureHandler;

    @Test
    void 해시_설정이_없으면_어떤_API_Key도_401이다() throws Exception {
        mockMvc.perform(put("/api/admin/creators/10/similar")
                        .header("X-Cking-Recommendation-Key", "any-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creatorId\":10,\"method\":\"M4\",\"modelVersion\":\"m\","
                                + "\"inputHash\":\"" + "a".repeat(64) + "\",\"applicationSequence\":1,\"candidates\":[]}"))
                .andExpect(status().isUnauthorized());

        then(resultService).should(never()).replace(any(), any());
    }
}

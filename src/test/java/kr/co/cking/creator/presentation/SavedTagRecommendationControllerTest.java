package kr.co.cking.creator.presentation;

import java.util.List;
import java.util.Map;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.creator.application.SavedTagRecommendationService;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SavedTagRecommendationController.class)
@org.springframework.context.annotation.Import({
    kr.co.cking.common.config.SecurityConfig.class,kr.co.cking.common.config.JwtConfig.class,
    kr.co.cking.common.config.CorsConfig.class,kr.co.cking.common.security.JwtAuthenticationConverterConfig.class,
    kr.co.cking.common.security.AccessTokenJwtValidator.class,kr.co.cking.common.security.RestAuthenticationEntryPoint.class,
    kr.co.cking.common.security.RestAccessDeniedHandler.class
})
@org.springframework.test.context.TestPropertySource(properties={
    "cking.auth.jwt.secret=2YNYNyIIJTSCD8zOXH/RpPp/Nm5+/n9gyVQpF5uRXlA="
})
class SavedTagRecommendationControllerTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @MockitoBean SavedTagRecommendationService service;
    @MockitoBean MemberRepository members;
    @MockitoBean kr.co.cking.auth.presentation.OAuth2LoginSuccessHandler success;
    @MockitoBean kr.co.cking.auth.presentation.OAuth2LoginFailureHandler failure;
    @Test void missingJwtCannotQueryOrReadCatalog() throws Exception {
        mvc.perform(get("/api/me/creator-recommendations/by-tags")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/creator-recommendations/tag-catalog")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    String token() {
        var now=java.time.Instant.now();
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("cking").subject("7")
                .claim("role","USER").issuedAt(now).expiresAt(now.plusSeconds(120)).build();
        return "Bearer "+encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    @Test void blankSelectionIsEmptyAndTrackingIsUnsupported() throws Exception {
        when(service.find(7,null,List.of(),List.of(),10)).thenReturn(Map.of("rankingStatus","READY","items",List.of()));
        mvc.perform(get("/api/me/creator-recommendations/by-tags").header("Authorization",token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        verify(service).find(7,null,List.of(),List.of(),10);
    }
    @Test void exactCodesArePassedWithoutSavingMemberInterests() throws Exception {
        when(service.find(7,"v1",List.of("FOOD"),List.of("FOOD_BAKING"),20)).thenReturn(Map.of("items",List.of()));
        mvc.perform(get("/api/me/creator-recommendations/by-tags").header("Authorization",token()).param("taxonomyVersion","v1")
                .param("parentCodes","FOOD").param("topicCodes","FOOD_BAKING").param("size","20"))
                .andExpect(status().isOk());
        verify(service).find(7,"v1",List.of("FOOD"),List.of("FOOD_BAKING"),20);
    }
    @Test void userJwtIsNotInternalBatchCredential() throws Exception {
        mvc.perform(get("/api/internal/content-tags/saved-recommendations/inputs").header("Authorization",token())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/internal/content-tags/saved-recommendations/rankings").header("Authorization",token()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}

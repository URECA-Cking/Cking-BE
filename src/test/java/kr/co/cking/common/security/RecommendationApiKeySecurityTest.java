package kr.co.cking.common.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.co.cking.auth.presentation.OAuth2LoginFailureHandler;
import kr.co.cking.auth.presentation.OAuth2LoginSuccessHandler;
import kr.co.cking.common.config.CorsConfig;
import kr.co.cking.common.config.JwtConfig;
import kr.co.cking.common.config.SecurityConfig;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import java.util.Optional;
import kr.co.cking.creator.application.CreatorSimilarityQueryService;
import kr.co.cking.creator.application.CreatorSimilarityResultService;
import kr.co.cking.creator.application.dto.CreatorSimilarityView;
import kr.co.cking.creator.presentation.CreatorSimilarityController;
import kr.co.cking.interest.application.InterestRecommendationResultService;
import kr.co.cking.interest.presentation.InterestRecommendationController;
import kr.co.cking.ticket.presentation.TicketAdminController;
import kr.co.cking.ticket.application.TicketAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 추천 적재 전용 API Key 인증이 적재 엔드포인트에서만, 계약한 우선순위로 동작하는지 검증한다. */
@WebMvcTest(controllers = {
        CreatorSimilarityController.class, InterestRecommendationController.class, TicketAdminController.class})
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
        "cking.auth.jwt.secret=2YNYNyIIJTSCD8zOXH/RpPp/Nm5+/n9gyVQpF5uRXlA=",
        // SHA-256("current-batch-key"), SHA-256("previous-batch-key"): 키 교체 중 두 키를 동시에 허용한다.
        "cking.recommendation.api-key-hashes="
                + "9027ed2295d01fc40ef03fd9029a3a499f246a50de41052811819fd884fe2341,"
                + "0d46307d90a97a45e14d429720c619005b591a917f71025ea443be67471d6c5e"
})
class RecommendationApiKeySecurityTest {

    private static final String KEY_HEADER = "X-Cking-Recommendation-Key";
    private static final String SIMILAR_URL = "/api/admin/creators/10/similar";

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean CreatorSimilarityResultService resultService;
    @MockitoBean CreatorSimilarityQueryService queryService;
    @MockitoBean InterestRecommendationResultService interestResultService;
    @MockitoBean MemberRepository memberRepository;
    @MockitoBean TicketAdminService ticketAdminService;
    @MockitoBean OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;
    @MockitoBean OAuth2LoginFailureHandler oauth2LoginFailureHandler;

    @Test
    void 유효한_API_Key로_JWT_없이_적재할_수_있다() throws Exception {
        stubStore();

        mockMvc.perform(putSimilar().header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));

        // 키 인증은 회원이 아니므로 DB에서 ADMIN을 확인하지 않는다.
        then(memberRepository).shouldHaveNoInteractions();
        then(resultService).should().replace(eq(10L), any());
    }

    @Test
    void 교체_중에는_이전_키도_함께_허용한다() throws Exception {
        stubStore();

        mockMvc.perform(putSimilar().header(KEY_HEADER, "previous-batch-key"))
                .andExpect(status().isOk());
    }

    @Test
    void 잘못된_API_Key는_401이다() throws Exception {
        mockMvc.perform(putSimilar().header(KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void 빈_API_Key_헤더도_401이다() throws Exception {
        mockMvc.perform(putSimilar().header(KEY_HEADER, ""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(putSimilar().header(KEY_HEADER, "  "))
                .andExpect(status().isUnauthorized());

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void 유효한_JWT가_있어도_잘못된_API_Key면_401이다() throws Exception {
        mockMvc.perform(putSimilar()
                        .header(KEY_HEADER, "wrong-key")
                        .header(AUTHORIZATION, "Bearer " + accessToken("ADMIN")))
                .andExpect(status().isUnauthorized());

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void 유효한_API_Key가_있으면_같이_보낸_JWT는_보지_않는다() throws Exception {
        stubStore();

        mockMvc.perform(putSimilar()
                        .header(KEY_HEADER, "current-batch-key")
                        .header(AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isOk());

        then(memberRepository).shouldHaveNoInteractions();
    }

    @Test
    void API_Key_헤더가_없으면_ADMIN_JWT로_적재하고_회원_ID로_ADMIN을_재확인한다() throws Exception {
        stubStore();
        given(memberRepository.findById(17L))
                .willReturn(Optional.of(new Member("admin", null, null, MemberRole.ADMIN)));

        mockMvc.perform(putSimilar().header(AUTHORIZATION, "Bearer " + accessToken("ADMIN")))
                .andExpect(status().isOk());

        then(memberRepository).should().findById(17L);
    }

    @Test
    void 일반_회원_JWT는_403이다() throws Exception {
        mockMvc.perform(putSimilar().header(AUTHORIZATION, "Bearer " + accessToken("USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}"));

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void 인증_정보가_없으면_401이다() throws Exception {
        mockMvc.perform(putSimilar())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void JWT가_ADMIN이어도_DB에서_ADMIN이_아니면_Application_재검증이_403으로_거부한다() throws Exception {
        given(memberRepository.findById(17L))
                .willReturn(Optional.of(new Member("demoted", null, null, MemberRole.USER)));

        mockMvc.perform(putSimilar().header(AUTHORIZATION, "Bearer " + accessToken("ADMIN")))
                .andExpect(status().isForbidden());

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void API_Key는_적재_엔드포인트_외_관리자_API에서는_인증으로_인정하지_않는다() throws Exception {
        mockMvc.perform(post("/api/admin/tickets/resync")
                        .header(KEY_HEADER, "current-batch-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":1,\"creatorId\":2,\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_경로라도_PUT이_아닌_메서드나_후행_슬래시에서는_키를_인증으로_인정하지_않는다() throws Exception {
        mockMvc.perform(get(SIMILAR_URL).header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(SIMILAR_URL).header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(putSimilar(SIMILAR_URL + "/").header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isUnauthorized());

        then(resultService).should(never()).replace(any(), any());
    }

    @Test
    void 공개_조회는_API_Key_헤더와_무관하게_인증_없이_동작한다() throws Exception {
        given(queryService.findSimilar(10L, 5)).willReturn(CreatorSimilarityView.empty(10L));

        mockMvc.perform(get("/api/creators/10/similar").header(KEY_HEADER, "wrong-key"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putSimilar() {
        return putSimilar(SIMILAR_URL);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putSimilar(String url) {
        return put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"creatorId":10,"method":"M4","modelVersion":"m","inputHash":"%s","candidates":[]}
                        """.formatted("a".repeat(64)));
    }

    private void stubStore() {
        given(resultService.replace(eq(10L), any()))
                .willReturn(new CreatorSimilarityResultService.StoreResult(10L, 100L, "a".repeat(64), 0, true));
    }

    private String accessToken(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("cking")
                .subject("17")
                .claim("role", role)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void 관심_분야_추천_적재도_같은_키_규칙을_따른다() throws Exception {
        given(interestResultService.replace(eq("SPORTS"), any())).willReturn(
                new InterestRecommendationResultService.StoreResult("v0.2", "SPORTS", 100L, "a".repeat(64), 0, true));

        mockMvc.perform(putInterest().header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isOk());
        mockMvc.perform(putInterest().header(KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(putInterest().header(KEY_HEADER, "wrong-key")
                        .header(AUTHORIZATION, "Bearer " + accessToken("ADMIN")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(putInterest().header(AUTHORIZATION, "Bearer " + accessToken("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(putInterest())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관심_분야_추천_적재_경로의_다른_메서드에서는_키를_인증으로_인정하지_않는다() throws Exception {
        mockMvc.perform(get("/api/admin/interests/SPORTS/recommendations").header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/interests/SPORTS/recommendations").header(KEY_HEADER, "current-batch-key"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putInterest() {
        String hash = "a".repeat(64);
        return put("/api/admin/interests/SPORTS/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxonomyVersion\":\"v0.2\",\"taxonomyHash\":\"" + hash + "\",\"interestCode\":\"SPORTS\","
                        + "\"method\":\"INTEREST_M3_V1\",\"modelVersion\":\"m\",\"inputHash\":\"" + hash + "\","
                        + "\"candidates\":[]}");
    }
}

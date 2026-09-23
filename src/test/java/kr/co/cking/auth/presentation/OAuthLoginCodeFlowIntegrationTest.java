package kr.co.cking.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.domain.OAuthProvider;
import kr.co.cking.auth.repository.OAuthAccountRepository;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** OAuth 성공 처리부터 Login Code 교환과 JWT Principal 주입까지의 실제 연결을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OAuthLoginCodeFlowIntegrationTest.CurrentMemberTestController.class)
class OAuthLoginCodeFlowIntegrationTest {

    @Autowired private OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;
    @Autowired private OAuthAccountRepository oauthAccountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private MockMvc mockMvc;

    private final List<OAuthIdentity> createdIdentities = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        for (OAuthIdentity identity : createdIdentities) {
            oauthAccountRepository.findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                    .map(OAuthAccount::getMemberId)
                    .ifPresent(memberId -> {
                        jdbcTemplate.update(
                                "DELETE FROM oauth_account WHERE provider = ? AND provider_user_id = ?",
                                identity.provider().name(), identity.providerUserId());
                        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
                    });
        }
    }

    @Test
    void Google_최초와_재로그인은_같은_Member의_LoginCode_JWT_Principal로_연결된다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        OAuthIdentity identity = new OAuthIdentity(OAuthProvider.GOOGLE, "google-sub-" + suffix);
        createdIdentities.add(identity);
        Map<String, Object> attributes = Map.of(
                "sub", identity.providerUserId(),
                "name", "Google 사용자",
                "email", "google-" + suffix + "@example.com");

        LoginResult firstLogin = loginAndExchange("google", "sub", attributes);
        LoginResult secondLogin = loginAndExchange("google", "sub", attributes);

        assertThat(secondLogin.memberId()).isEqualTo(firstLogin.memberId());
        assertThat(jwtDecoder.decode(firstLogin.accessToken()).getSubject())
                .isEqualTo(firstLogin.memberId().toString());
        assertThat(jwtDecoder.decode(firstLogin.accessToken()).getClaimAsString("role")).isEqualTo("USER");

        mockMvc.perform(get("/api/test/current-member")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstLogin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(firstLogin.memberId()));
    }

    @Test
    void Kakao_최초와_재로그인은_정수_ID로_같은_Member_LoginCode_JWT에_연결된다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String kakaoId = Long.toString(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        OAuthIdentity identity = new OAuthIdentity(OAuthProvider.KAKAO, kakaoId);
        createdIdentities.add(identity);
        Map<String, Object> attributes = Map.of(
                "id", Long.parseLong(kakaoId),
                "properties", Map.of("nickname", "Kakao 사용자"),
                "kakao_account", Map.of("email", "kakao-" + suffix + "@example.com"));

        LoginResult firstLogin = loginAndExchange("kakao", "id", attributes);
        LoginResult secondLogin = loginAndExchange("kakao", "id", attributes);

        assertThat(firstLogin.memberId()).isPositive();
        assertThat(secondLogin.memberId()).isEqualTo(firstLogin.memberId());
        Jwt accessToken = jwtDecoder.decode(firstLogin.accessToken());
        assertThat(accessToken.getSubject()).isEqualTo(firstLogin.memberId().toString());
        assertThat(accessToken.getClaimAsString("iss")).isEqualTo("cking");
    }

    @Test
    void 같은_이메일의_Google과_Kakao는_각각_다른_Member로_연결된다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String sharedEmail = "shared-" + suffix + "@example.com";
        OAuthIdentity google = new OAuthIdentity(OAuthProvider.GOOGLE, "shared-google-" + suffix);
        String kakaoId = Long.toString(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        OAuthIdentity kakao = new OAuthIdentity(OAuthProvider.KAKAO, kakaoId);
        createdIdentities.addAll(List.of(google, kakao));

        LoginResult googleLogin = loginAndExchange("google", "sub", Map.of(
                "sub", google.providerUserId(), "name", "Google 사용자", "email", sharedEmail));
        LoginResult kakaoLogin = loginAndExchange("kakao", "id", Map.of(
                "id", Long.parseLong(kakao.providerUserId()),
                "properties", Map.of("nickname", "Kakao 사용자"),
                "kakao_account", Map.of("email", sharedEmail)));

        assertThat(kakaoLogin.memberId()).isNotEqualTo(googleLogin.memberId());
    }

    @Test
    void LoginCode는_교환에_한번만_사용할_수_있다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        OAuthIdentity identity = new OAuthIdentity(OAuthProvider.GOOGLE, "single-use-sub-" + suffix);
        createdIdentities.add(identity);
        String code = completeOAuthLogin("google", "sub", Map.of(
                "sub", identity.providerUserId(),
                "name", "일회용 코드 사용자",
                "email", "single-use-" + suffix + "@example.com"));

        exchange(code).andExpect(status().isOk());
        exchange(code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_LOGIN_CODE"));
    }

    @Test
    void 잘못된_Kakao_ID는_Member나_LoginCode를_생성하지_않고_실패_redirect한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "not-a-number"), "id");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                user, user.getAuthorities(), "kakao");

        oauth2LoginSuccessHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/oauth/callback?error=login_processing_failed");
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "not-a-number"))
                .isEmpty();
    }

    private LoginResult loginAndExchange(String registrationId, String nameAttributeKey, Map<String, Object> attributes)
            throws Exception {
        String code = completeOAuthLogin(registrationId, nameAttributeKey, attributes);
        MvcResult result = exchange(code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String accessToken = data.path("accessToken").asText();
        Jwt decodedToken = jwtDecoder.decode(accessToken);
        return new LoginResult(Long.valueOf(decodedToken.getSubject()), accessToken);
    }

    private String completeOAuthLogin(String registrationId, String nameAttributeKey, Map<String, Object> attributes)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, nameAttributeKey);
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                user, user.getAuthorities(), registrationId);

        oauth2LoginSuccessHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(session.isInvalid()).isTrue();
        String redirectUrl = response.getRedirectedUrl();
        assertThat(redirectUrl).startsWith("http://localhost:5173/oauth/callback?code=");
        var queryParams = UriComponentsBuilder.fromUriString(redirectUrl).build().getQueryParams();
        assertThat(queryParams).containsOnlyKeys("code");
        return queryParams.getFirst("code");
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String code) throws Exception {
        return mockMvc.perform(post("/api/auth/token")
                .contentType("application/json")
                .content("{\"code\":\"%s\"}".formatted(code)));
    }

    private record OAuthIdentity(OAuthProvider provider, String providerUserId) {
    }

    private record LoginResult(Long memberId, String accessToken) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class CurrentMemberTestController {

        @GetMapping("/api/test/current-member")
        ApiResponse<Long> currentMember(@CurrentMemberId Long memberId) {
            return ApiResponse.success(memberId);
        }
    }
}

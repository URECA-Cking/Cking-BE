package kr.co.cking.common.config;

import java.time.Instant;
import java.util.List;
import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.RefreshTokenService;
import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.auth.presentation.AuthController;
import kr.co.cking.auth.presentation.OAuth2LoginFailureHandler;
import kr.co.cking.auth.presentation.OAuth2LoginSuccessHandler;
import kr.co.cking.auth.presentation.RefreshRequestOriginValidator;
import kr.co.cking.auth.presentation.RefreshTokenCookieFactory;
import kr.co.cking.common.security.AccessTokenJwtValidator;
import kr.co.cking.common.security.JwtAuthenticationConverterConfig;
import kr.co.cking.common.security.RestAccessDeniedHandler;
import kr.co.cking.common.security.RestAuthenticationEntryPoint;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.presentation.MemberController;
import kr.co.cking.member.presentation.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spring Security 기반 설정이 기존 API와 문서 인증 경로에 미치는 영향을 검증한다. */
@WebMvcTest(controllers = {MemberController.class, AuthController.class})
@Import({
        SecurityConfig.class,
        JwtConfig.class,
        JwtAuthenticationConverterConfig.class,
        CorsConfig.class,
        AccessTokenJwtValidator.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
@TestPropertySource(properties = {
        "cking.cors.allowed-origins=https://frontend.cking.co.kr",
        "cking.cors.allow-credentials=false",
        "cking.auth.jwt.secret=2YNYNyIIJTSCD8zOXH/RpPp/Nm5+/n9gyVQpF5uRXlA="
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private MemberQueryService memberQueryService;

    @MockitoBean
    private LoginCodeService loginCodeService;

    @MockitoBean
    private AccessTokenService accessTokenService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private RefreshTokenCookieFactory refreshTokenCookieFactory;

    @MockitoBean
    private RefreshRequestOriginValidator refreshRequestOriginValidator;

    @MockitoBean
    private OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginFailureHandler oauth2LoginFailureHandler;

    /** 인증 전환 전 API가 인증 없이도 기존처럼 호출되는지 검증한다. */
    @Test
    void 인증_전환_전_API는_인증_없이_호출할_수_있다() throws Exception {
        when(memberQueryService.findUsers()).thenReturn(List.of(new UserSummary(1L, "홍길동")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));
    }

    /** 인증된 요청도 기존 API의 공개 동작을 바꾸지 않는지 검증한다. */
    @Test
    @WithMockUser(username = "member-1")
    void 인증된_호출자도_기존_API를_호출할_수_있다() throws Exception {
        when(memberQueryService.findUsers()).thenReturn(List.of(new UserSummary(1L, "홍길동")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));
    }

    /** 서명 또는 형식이 잘못된 Bearer JWT는 공통 UNAUTHORIZED 응답으로 거절하는지 검증한다. */
    @Test
    void 유효하지_않은_Bearer_JWT는_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/users").header(AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** 만료된 Access JWT가 자동 첨부되어도 Login Code 교환을 차단하지 않는다. */
    @Test
    void 만료된_Access_JWT와_LoginCode로_AccessToken을_발급한다() throws Exception {
        when(loginCodeService.consume("one-time-code")).thenReturn(17L);
        when(refreshTokenService.issue(17L)).thenReturn("refresh-token");
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_LOGIN_CODE))
                .thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));
        when(refreshTokenCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "refresh-token").build());

        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"code\":\"one-time-code\"}")
                        .header(AUTHORIZATION, "Bearer " + expiredAccessToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(loginCodeService).consume("one-time-code");
    }

    /** 만료된 Access JWT가 자동 첨부되어도 Refresh Cookie 인증 흐름을 차단하지 않는다. */
    @Test
    void 만료된_Access_JWT와_RefreshCookie로_AccessToken을_갱신한다() throws Exception {
        when(refreshTokenService.rotate("refresh-token"))
                .thenReturn(new RefreshTokenRotationResult(17L, "next-refresh-token"));
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_REFRESH_TOKEN))
                .thenReturn(new AccessTokenResult("next-access-token", "Bearer", 1800));
        when(refreshTokenCookieFactory.create("next-refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "next-refresh-token").build());

        mockMvc.perform(post("/api/auth/refresh")
                        .header(AUTHORIZATION, "Bearer " + expiredAccessToken())
                        .header(ORIGIN, "https://frontend.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(refreshTokenService).rotate("refresh-token");
    }

    /** 만료된 Access JWT가 자동 첨부되어도 Logout의 Refresh Cookie 정리를 차단하지 않는다. */
    @Test
    void 만료된_Access_JWT와_RefreshCookie로_Logout한다() throws Exception {
        when(refreshTokenCookieFactory.expire())
                .thenReturn(ResponseCookie.from("refresh_token", "").maxAge(0).build());

        mockMvc.perform(post("/api/auth/logout")
                        .header(AUTHORIZATION, "Bearer " + expiredAccessToken())
                        .header(ORIGIN, "https://frontend.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(refreshTokenService).revoke("refresh-token");
    }

    /** Authorization 헤더를 포함한 허용 origin의 사전 요청을 처리하는지 검증한다. */
    @Test
    void 허용된_origin의_CORS_사전_요청을_처리한다() throws Exception {
        mockMvc.perform(options("/api/users")
                        .header(ORIGIN, "https://frontend.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://frontend.cking.co.kr"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_HEADERS))
                        .containsIgnoringCase("Authorization"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS))
                        .isNull());
    }

    /** 문서 계정이 없는 로컬 환경에서는 Swagger 경로를 Basic Auth 없이 처리하는지 검증한다. */
    @Test
    void Swagger_경로는_Security_인증으로_거부되지_않는다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    /** SpringDoc 기본 진입 경로도 문서 보안 체인에서 처리하는지 검증한다. */
    @Test
    void Swagger_기본_진입_경로는_Security_인증으로_거부되지_않는다() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    private String expiredAccessToken() {
        Instant expiresAt = Instant.now().minusSeconds(120);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("cking")
                .subject("17")
                .claim("role", "USER")
                .issuedAt(expiresAt.minusSeconds(60))
                .expiresAt(expiresAt)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

}

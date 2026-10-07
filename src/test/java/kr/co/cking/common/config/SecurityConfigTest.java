package kr.co.cking.common.config;

import java.time.Instant;
import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.AdminAuthService;
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
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.application.MemberProfile;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.presentation.MemberController;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS;
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
        SecurityConfigTest.AdminSecurityTestController.class,
        SecurityConfig.class,
        JwtConfig.class,
        JwtAuthenticationConverterConfig.class,
        CorsConfig.class,
        RefreshRequestOriginValidator.class,
        AccessTokenJwtValidator.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
@TestPropertySource(properties = {
        "cking.cors.allowed-origins=https://dev.cking.co.kr,https://dev-admin.cking.co.kr",
        "cking.cors.allow-credentials=true",
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
    private CreatorQueryService creatorQueryService;

    @MockitoBean
    private AdminAuthService adminAuthService;

    @MockitoBean
    private LoginCodeService loginCodeService;

    @MockitoBean
    private AccessTokenService accessTokenService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private RefreshTokenCookieFactory refreshTokenCookieFactory;

    @MockitoBean
    private OAuth2LoginSuccessHandler oauth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginFailureHandler oauth2LoginFailureHandler;

    /** 인증 전환 전 Event API 경로가 인증 없이 Security에서 차단되지 않는지 검증한다. */
    @Test
    void 인증_전환_전_API는_인증_없이_호출할_수_있다() throws Exception {
        mockMvc.perform(get("/api/events"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    /** 서명 또는 형식이 잘못된 Bearer JWT는 공통 UNAUTHORIZED 응답으로 거절하는지 검증한다. */
    @Test
    void 유효하지_않은_Bearer_JWT는_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/events").header(AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** 현재 사용자 API는 유효한 Access JWT가 없으면 호출할 수 없다. */
    @Test
    void 현재_사용자_API는_미인증_요청을_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** 유효 JWT의 subject가 Controller를 거쳐 현재 사용자 조회 서비스로 전달된다. */
    @Test
    void 유효한_Access_JWT로_현재_사용자를_조회한다() throws Exception {
        when(memberQueryService.getProfile(17L))
                .thenReturn(new MemberProfile(17L, "홍길동", "hong@example.com", MemberRole.USER));
        when(creatorQueryService.isCreatorMember(17L)).thenReturn(false);

        mockMvc.perform(get("/api/me")
                        .header(AUTHORIZATION, "Bearer " + validAccessToken("USER")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"memberId\":17", "\"creator\":false"));
    }

    /** 모든 ADMIN API는 Access JWT 없이 호출할 수 없다. */
    @Test
    void ADMIN_API는_미인증_요청을_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/admin/dead-streams"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** USER JWT는 모든 ADMIN API의 1차 인가에서 거절한다. */
    @Test
    void USER_JWT는_ADMIN_API를_403으로_거절한다() throws Exception {
        mockMvc.perform(get("/api/admin/events")
                        .header(AUTHORIZATION, "Bearer " + validAccessToken("USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\"}"));
    }

    /** ADMIN JWT는 기존 관리자 경로의 Security 인가를 통과하는지 검증한다. */
    @Test
    void ADMIN_JWT는_ADMIN_API를_정상_호출한다() throws Exception {
        mockMvc.perform(get("/api/admin/events")
                        .header(AUTHORIZATION, "Bearer " + validAccessToken("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-api"));
    }

    /** USER·ADMIN 공용 Winner 이력 API도 JWT 인증 없이는 호출할 수 없다. */
    @Test
    void Winner_상태_이력_API는_미인증_요청을_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/winners/10/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** 개인 캘린더 API도 JWT 인증 없이는 호출할 수 없다. */
    @Test
    void 개인_캘린더_API는_미인증_요청을_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/me/calendar/schedules"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** 개인화 Creator 추천 API도 JWT 인증 없이는 호출할 수 없다. */
    @Test
    void 개인화_Creator_추천_API는_미인증_요청을_401로_거절한다() throws Exception {
        mockMvc.perform(get("/api/me/creator-recommendations"))
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

    /** 만료된 Access JWT가 자동 첨부되어도 관리자 ID/PW 로그인을 차단하지 않는다. */
    @Test
    void 만료된_Access_JWT와_관리자_자격증명으로_로그인한다() throws Exception {
        when(adminAuthService.authenticate("admin", "password")).thenReturn(17L);
        when(refreshTokenService.issue(17L)).thenReturn("refresh-token");
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_ADMIN_CREDENTIALS))
                .thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));
        when(refreshTokenCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "refresh-token").build());

        mockMvc.perform(post("/api/auth/admin/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"admin\",\"password\":\"password\"}")
                        .header(AUTHORIZATION, "Bearer " + expiredAccessToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(adminAuthService).authenticate("admin", "password");
    }

    /** Authorization 헤더 없이도 관리자 로그인 Controller까지 도달하는지 검증한다. */
    @Test
    void 인증_없이_관리자_자격증명으로_로그인한다() throws Exception {
        when(adminAuthService.authenticate("admin", "password")).thenReturn(17L);
        when(refreshTokenService.issue(17L)).thenReturn("refresh-token");
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_ADMIN_CREDENTIALS))
                .thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));
        when(refreshTokenCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "refresh-token").build());

        mockMvc.perform(post("/api/auth/admin/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(adminAuthService).authenticate("admin", "password");
    }

    /** 형식이 잘못된 Bearer JWT가 있어도 관리자 로그인 Controller까지 도달하는지 검증한다. */
    @Test
    void 잘못된_Bearer_JWT와_관리자_자격증명으로_로그인한다() throws Exception {
        when(adminAuthService.authenticate("admin", "password")).thenReturn(17L);
        when(refreshTokenService.issue(17L)).thenReturn("refresh-token");
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_ADMIN_CREDENTIALS))
                .thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));
        when(refreshTokenCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "refresh-token").build());

        mockMvc.perform(post("/api/auth/admin/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"admin\",\"password\":\"password\"}")
                        .header(AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(adminAuthService).authenticate("admin", "password");
    }

    /** 관리자 Web Origin에서도 만료된 Access JWT와 무관하게 Refresh Cookie 인증 흐름을 처리한다. */
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
                        .header(ORIGIN, "https://dev-admin.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(refreshTokenService).rotate("refresh-token");
    }

    /** 관리자 Web Origin에서도 만료된 Access JWT와 무관하게 Logout의 Refresh Cookie를 정리한다. */
    @Test
    void 만료된_Access_JWT와_RefreshCookie로_Logout한다() throws Exception {
        when(refreshTokenCookieFactory.expire())
                .thenReturn(ResponseCookie.from("refresh_token", "").maxAge(0).build());

        mockMvc.perform(post("/api/auth/logout")
                        .header(AUTHORIZATION, "Bearer " + expiredAccessToken())
                        .header(ORIGIN, "https://dev-admin.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"SUCCESS\"}"));

        verify(refreshTokenService).revoke("refresh-token");
    }

    /** 사용자 Web Origin의 credential 포함 일반 API 사전 요청을 처리하는지 검증한다. */
    @Test
    void 사용자_Web_Origin의_일반_API_CORS_사전_요청을_처리한다() throws Exception {
        mockMvc.perform(options("/api/me")
                        .header(ORIGIN, "https://dev.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://dev.cking.co.kr"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_HEADERS))
                        .containsIgnoringCase("Authorization"))
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    /** 사용자 Web Origin의 PUT API 사전 요청이 허용 메서드 목록에서 누락되지 않는지 검증한다(이슈 #357). */
    @Test
    void 사용자_Web_Origin의_PUT_API_CORS_사전_요청을_처리한다() throws Exception {
        mockMvc.perform(options("/api/creators/1/follow")
                        .header(ORIGIN, "https://dev.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://dev.cking.co.kr"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_METHODS))
                        .contains("PUT"));
    }

    /** 관리자 Web Origin의 credential 포함 관리자 API 사전 요청을 처리하는지 검증한다. */
    @Test
    void 관리자_Web_Origin의_관리자_API_CORS_사전_요청을_처리한다() throws Exception {
        mockMvc.perform(options("/api/admin/dead-streams")
                        .header(ORIGIN, "https://dev-admin.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://dev-admin.cking.co.kr"))
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    /** 설정에 없는 Origin의 credential 포함 사전 요청을 CORS가 차단하는지 검증한다. */
    @Test
    void 알_수_없는_Origin의_CORS_사전_요청을_차단한다() throws Exception {
        mockMvc.perform(options("/api/admin/dead-streams")
                        .header(ORIGIN, "https://unknown.cking.co.kr")
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getHeader(ACCESS_CONTROL_ALLOW_ORIGIN))
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

    /** 만료된 Access JWT가 Auth 인증 예외 경로를 우회하는지 확인할 테스트 토큰을 만든다. */
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

    /** 지정 역할로 보호된 경로의 인가를 확인할 유효 Access JWT를 만든다. */
    private String validAccessToken(String role) {
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

    /** ADMIN 역할 JWT가 통과한 뒤 테스트에서 사용할 최소 관리자 API 응답을 제공한다. */
    @RestController
    static class AdminSecurityTestController {

        /** 관리자 JWT가 인가된 요청에 성공 응답을 반환한다. */
        @GetMapping("/api/admin/dead-streams")
        String accessAdminApi() {
            return "admin-api";
        }

        /** 관리자 Event 운영 목록 경로가 인가된 요청에 성공 응답을 반환한다. */
        @GetMapping("/api/admin/events")
        String accessManagedEventList() {
            return "admin-api";
        }
    }

}

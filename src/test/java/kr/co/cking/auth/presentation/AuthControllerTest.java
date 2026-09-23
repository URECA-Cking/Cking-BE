package kr.co.cking.auth.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.RefreshTokenService;
import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Login Code 교환 API의 공통 응답과 입력 검증을 확인한다. */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    /** 유효한 Login Code를 Access Token 공통 응답으로 바꾸는지 검증한다. */
    @Test
    void LoginCode를_AccessToken으로_교환한다() throws Exception {
        when(loginCodeService.consume("one-time-code")).thenReturn(17L);
        when(accessTokenService.issue(17L)).thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));
        when(refreshTokenService.issue(17L)).thenReturn("refresh-token");
        when(refreshTokenCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "refresh-token").build());

        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"code\":\"one-time-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800));

        verify(loginCodeService).consume("one-time-code");
        verify(accessTokenService).issue(17L);
        verify(refreshTokenService).issue(17L);
    }

    /** 비어 있는 Login Code 요청은 Access Token 발급 전에 입력 오류로 거절하는지 검증한다. */
    @Test
    void LoginCode가_비어_있으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Refresh Cookie가 회전되면 새 Access Token과 Set-Cookie 헤더를 반환하는지 검증한다. */
    @Test
    void RefreshToken을_회전해_AccessToken을_갱신한다() throws Exception {
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult(17L, "next-refresh-token"));
        when(accessTokenService.issue(17L)).thenReturn(new AccessTokenResult("next-access-token", "Bearer", 1800));
        when(refreshTokenCookieFactory.create("next-refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "next-refresh-token").build());

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://dev.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("next-access-token"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("Set-Cookie")).contains("refresh_token=next-refresh-token"));

        verify(refreshRequestOriginValidator).validate("https://dev.cking.co.kr");
        verify(refreshTokenService).rotate("old-refresh-token");
    }

    /** Logout이 Redis 폐기 호출과 만료 Cookie 응답을 함께 수행하는지 검증한다. */
    @Test
    void Logout은_RefreshToken을_폐기하고_Cookie를_만료한다() throws Exception {
        when(refreshTokenCookieFactory.expire())
                .thenReturn(ResponseCookie.from("refresh_token", "").maxAge(0).build());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Origin", "https://dev.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0"));

        verify(refreshRequestOriginValidator).validate("https://dev.cking.co.kr");
        verify(refreshTokenService).revoke("refresh-token");
    }
}

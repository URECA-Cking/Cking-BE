package kr.co.cking.auth.presentation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.RefreshTokenService;
import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
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
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_LOGIN_CODE))
                .thenReturn(new AccessTokenResult("access-token", "Bearer", 1800));
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
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("Set-Cookie")).contains("refresh_token=refresh-token"));

        verify(loginCodeService).consume("one-time-code");
        verify(accessTokenService).issue(17L, AuthErrorCode.INVALID_LOGIN_CODE);
        verify(refreshTokenService).issue(17L);
    }

    /** Login Code 교환의 응답 생성이 실패하면 클라이언트에 전달되지 않은 Refresh Token을 폐기한다. */
    @Test
    void LoginCode_AccessToken발급_실패시_RefreshToken을_폐기한다() throws Exception {
        when(loginCodeService.consume("one-time-code")).thenReturn(17L);
        when(refreshTokenService.issue(17L)).thenReturn("refresh-token");
        when(refreshTokenCookieFactory.create("refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "refresh-token").build());
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_LOGIN_CODE))
                .thenThrow(new BusinessException(AuthErrorCode.INVALID_LOGIN_CODE));

        mockMvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"code\":\"one-time-code\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_LOGIN_CODE"));

        verify(refreshTokenService).revoke("refresh-token");
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
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_REFRESH_TOKEN))
                .thenReturn(new AccessTokenResult("next-access-token", "Bearer", 1800));
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

    /** 삭제된 Member의 Refresh Token은 회전 뒤 다음 Token을 폐기하고 Refresh 오류로 통합한다. */
    @Test
    void 존재하지_않는_Member의_RefreshToken은_401로_거절한다() throws Exception {
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult(17L, "next-refresh-token"));
        when(refreshTokenCookieFactory.create("next-refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "next-refresh-token").build());
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_REFRESH_TOKEN))
                .thenThrow(new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://dev.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        verify(refreshTokenService).revoke("next-refresh-token");
    }

    /** 응답 생성 중 예기치 않은 오류가 나도 클라이언트에 전달되지 않은 다음 Token을 폐기한다. */
    @Test
    void Refresh_응답생성_중_런타임오류가_나면_다음Token을_폐기한다() throws Exception {
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult(17L, "next-refresh-token"));
        when(accessTokenService.issue(17L, AuthErrorCode.INVALID_REFRESH_TOKEN))
                .thenThrow(new IllegalStateException("JWT 발급 실패"));
        when(refreshTokenCookieFactory.create("next-refresh-token"))
                .thenReturn(ResponseCookie.from("refresh_token", "next-refresh-token").build());

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://dev.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh-token")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));

        verify(refreshTokenService).revoke("next-refresh-token");
    }

    /** Refresh Cookie가 없으면 Refresh Token 오류 계약으로 거절하는지 검증한다. */
    @Test
    void RefreshCookie가_없으면_401로_거절한다() throws Exception {
        when(refreshTokenService.rotate(null))
                .thenThrow(new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://dev.cking.co.kr"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        verify(refreshTokenService).rotate(null);
    }

    /** 만료·폐기된 Refresh Token은 세부 사유와 관계없이 같은 401 계약으로 응답한다. */
    @Test
    void 만료되거나_폐기된_RefreshToken은_401로_거절한다() throws Exception {
        when(refreshTokenService.rotate("expired-or-revoked-token"))
                .thenThrow(new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://dev.cking.co.kr")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "expired-or-revoked-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    /** 허용되지 않은 Origin은 Refresh Token을 소비하기 전에 CSRF 방어 오류로 거절한다. */
    @Test
    void 허용되지_않은_Origin의_Refresh는_403으로_거절한다() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(refreshRequestOriginValidator).validate("https://attacker.example");

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://attacker.example")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(refreshTokenService);
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

    /** Cookie가 없는 Logout도 만료 Cookie를 반환해 클라이언트의 정리를 성공으로 처리한다. */
    @Test
    void RefreshCookie가_없는_Logout도_성공한다() throws Exception {
        when(refreshTokenCookieFactory.expire())
                .thenReturn(ResponseCookie.from("refresh_token", "").maxAge(0).build());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Origin", "https://dev.cking.co.kr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0"));

        verify(refreshTokenService).revoke(null);
    }
}

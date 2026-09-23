package kr.co.cking.auth.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.security.oauth.OAuthMemberLoginService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

/** OAuth 성공·실패 redirect가 외부 계약과 세션 정리를 지키는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginHandlerTest {

    @Mock private OAuthMemberLoginService oauthMemberLoginService;
    @Mock private LoginCodeService loginCodeService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private HttpSession session;
    @InjectMocks private OAuth2LoginSuccessHandler successHandler;
    @InjectMocks private OAuth2LoginFailureHandler failureHandler;

    /** 성공 시 Login Code만 callback query에 담고 OAuth session을 비우는지 검증한다. */
    @Test
    void OAuth_성공_후_LoginCode만_담아_redirect하고_세션을_정리한다() throws Exception {
        configureCallbackUrl();
        OAuth2AuthenticationToken authentication = authentication();
        when(oauthMemberLoginService.login("google", authentication.getPrincipal().getAttributes())).thenReturn(31L);
        when(loginCodeService.issue(31L)).thenReturn("one-time-code");
        when(request.getSession(false)).thenReturn(session);

        successHandler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("https://frontend.cking.co.kr/oauth/callback?code=one-time-code");
        verify(session).invalidate();
    }

    /** 성공 처리 중 오류는 세부 원인 대신 계약된 오류 값으로 redirect하는지 검증한다. */
    @Test
    void OAuth_성공_처리_실패는_계약된_error로_redirect한다() throws Exception {
        configureCallbackUrl();
        OAuth2AuthenticationToken authentication = authentication();
        when(oauthMemberLoginService.login("google", authentication.getPrincipal().getAttributes()))
                .thenThrow(new IllegalArgumentException("provider detail"));
        when(request.getSession(false)).thenReturn(session);

        successHandler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("https://frontend.cking.co.kr/oauth/callback?error=login_processing_failed");
        verify(session).invalidate();
    }

    /** 사용자의 동의 거부만 access_denied로 전달하고 session을 정리하는지 검증한다. */
    @Test
    void OAuth_동의_거부는_access_denied로_redirect한다() throws Exception {
        configureCallbackUrl();
        when(request.getSession(false)).thenReturn(session);

        failureHandler.onAuthenticationFailure(
                request, response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        verify(response).sendRedirect("https://frontend.cking.co.kr/oauth/callback?error=access_denied");
        verify(session).invalidate();
    }

    /** Provider의 다른 오류는 세부 정보를 감춘 provider_error로 통합하는지 검증한다. */
    @Test
    void OAuth_Provider_오류는_provider_error로_redirect한다() throws Exception {
        configureCallbackUrl();
        when(request.getSession(false)).thenReturn(session);

        failureHandler.onAuthenticationFailure(
                request, response, new OAuth2AuthenticationException(new OAuth2Error("invalid_scope")));

        verify(response).sendRedirect("https://frontend.cking.co.kr/oauth/callback?error=provider_error");
        verify(session).invalidate();
    }

    /** 테스트 대상 Handler에 Frontend callback URL을 주입한다. */
    private void configureCallbackUrl() {
        ReflectionTestUtils.setField(successHandler, "frontendCallbackUrl", "https://frontend.cking.co.kr/oauth/callback");
        ReflectionTestUtils.setField(failureHandler, "frontendCallbackUrl", "https://frontend.cking.co.kr/oauth/callback");
    }

    /** Google 성공 흐름에 사용할 최소 OAuth2 인증 토큰을 만든다. */
    private OAuth2AuthenticationToken authentication() {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "sub"), "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }
}

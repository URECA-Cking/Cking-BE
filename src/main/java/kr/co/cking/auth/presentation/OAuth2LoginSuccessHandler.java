package kr.co.cking.auth.presentation;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.OAuthLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** OAuth 로그인 완료 후 1회용 Login Code만 Frontend callback에 전달한다. */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthUserInfoResolver oauthUserInfoResolver;
    private final OAuthLoginService oauthLoginService;
    private final LoginCodeService loginCodeService;
    @Value("${cking.auth.frontend-callback-url}")
    private String frontendCallbackUrl;

    /** OAuth 사용자 정보를 Member에 연결하고 성공 또는 처리 실패 callback으로 redirect한다. */
    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        try {
            OAuth2AuthenticationToken oauthAuthentication = requireOAuthAuthentication(authentication);
            Long memberId = oauthLoginService.login(oauthUserInfoResolver.resolve(oauthAuthentication));
            redirect(response, "code", loginCodeService.issue(memberId));
        } catch (RuntimeException exception) {
            redirect(response, "error", "login_processing_failed");
        } finally {
            clearSession(request);
        }
    }

    /** OAuth2 인증 토큰만 성공 처리 경계로 통과시키고 다른 인증 형식은 거부한다. */
    private OAuth2AuthenticationToken requireOAuthAuthentication(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauthAuthentication) {
            return oauthAuthentication;
        }
        throw new IllegalArgumentException("OAuth2 인증 토큰이 필요합니다.");
    }

    /** callback URL의 기존 query를 제거하고 계약된 단일 parameter로 redirect한다. */
    private void redirect(HttpServletResponse response, String parameterName, String parameterValue) throws IOException {
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .replaceQuery(null)
                .queryParam(parameterName, parameterValue)
                .build()
                .encode()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }

    /** OAuth authorization request를 포함한 임시 HTTP session을 무효화한다. */
    private void clearSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}

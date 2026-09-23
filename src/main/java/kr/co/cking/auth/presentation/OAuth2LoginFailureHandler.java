package kr.co.cking.auth.presentation;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** OAuth 로그인 실패 원인을 외부 계약 오류 코드로 제한해 Frontend에 전달한다. */
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Value("${cking.auth.frontend-callback-url:http://localhost:5173/oauth/callback}")
    private String frontendCallbackUrl;

    /** 실패 세부 정보를 노출하지 않고 허용된 error 값으로 callback redirect한다. */
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        try {
            redirect(response, errorCode(exception));
        } finally {
            clearSession(request);
        }
    }

    /** 사용자의 명시적 거부만 access_denied로 보존하고 나머지는 provider_error로 통합한다. */
    private String errorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauthException
                && "access_denied".equals(oauthException.getError().getErrorCode())) {
            return "access_denied";
        }
        return "provider_error";
    }

    /** callback URL의 기존 query를 제거하고 error 하나만 포함해 redirect한다. */
    private void redirect(HttpServletResponse response, String error) throws IOException {
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .replaceQuery(null)
                .queryParam("error", error)
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

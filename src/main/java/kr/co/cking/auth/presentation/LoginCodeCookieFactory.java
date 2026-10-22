package kr.co.cking.auth.presentation;

import java.time.Duration;

import kr.co.cking.auth.application.LoginCodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Login Code를 받은 브라우저만 교환할 수 있도록, 교환 API에만 보내지는 쿠키를 만든다. */
@Component
public class LoginCodeCookieFactory {

    public static final String COOKIE_NAME = "cking_login_code";
    private static final String COOKIE_PATH = "/api/auth/token";

    private final boolean secure;

    public LoginCodeCookieFactory(@Value("${cking.auth.refresh-cookie-secure}") boolean secure) {
        this.secure = secure;
    }

    /** Login Code와 같은 시간 동안 유지되는 브라우저 값 쿠키를 만든다. */
    public ResponseCookie create(String browserBinding) {
        return cookie(browserBinding, LoginCodeService.TTL);
    }

    /** 교환이 끝난 브라우저 값 쿠키를 즉시 만료한다. */
    public ResponseCookie expire() {
        return cookie("", Duration.ZERO);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}

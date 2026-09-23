package kr.co.cking.auth.presentation;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Refresh Token의 고정 Cookie 속성을 일관되게 생성한다. */
@Component
public class RefreshTokenCookieFactory {

    private static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";
    private static final String SAME_SITE = "Lax";

    private final Duration refreshTokenTtl;
    private final boolean secure;

    /** Refresh Token TTL과 HTTPS 환경의 Secure 여부를 설정값에서 받는다. */
    public RefreshTokenCookieFactory(
            @Value("${cking.auth.refresh-token-ttl:P14D}") Duration refreshTokenTtl,
            @Value("${cking.auth.refresh-cookie-secure:false}") boolean secure
    ) {
        this.refreshTokenTtl = refreshTokenTtl;
        this.secure = secure;
    }

    /** 새 opaque Token을 담은 HttpOnly same-site Cookie를 발급한다. */
    public ResponseCookie create(String refreshToken) {
        return cookieBuilder(refreshToken).maxAge(refreshTokenTtl).build();
    }

    /** 브라우저의 기존 Refresh Token Cookie를 즉시 만료한다. */
    public ResponseCookie expire() {
        return cookieBuilder("").maxAge(Duration.ZERO).build();
    }

    /** Refresh Cookie의 이름·경로·보안 속성을 공통으로 설정한다. */
    private ResponseCookie.ResponseCookieBuilder cookieBuilder(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(COOKIE_PATH);
    }
}

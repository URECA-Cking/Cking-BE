package kr.co.cking.auth.presentation;

import java.time.Duration;
import java.util.Objects;

import kr.co.cking.auth.domain.RefreshSessionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Refresh Token의 고정 Cookie 속성을 일관되게 생성한다. */
@Component
public class RefreshTokenCookieFactory {

    private static final String USER_COOKIE_NAME = "refresh_token";
    private static final String USER_COOKIE_PATH = "/api/auth";
    private static final String ADMIN_COOKIE_NAME = "admin_refresh_token";
    private static final String ADMIN_COOKIE_PATH = "/api/admin/auth";
    private static final String SAME_SITE = "Lax";

    private final Duration refreshTokenTtl;
    private final boolean secure;

    /** Refresh Token TTL과 HTTPS 환경의 Secure 여부를 설정값에서 받는다. */
    public RefreshTokenCookieFactory(
            @Value("${cking.auth.refresh-token-ttl:P14D}") Duration refreshTokenTtl,
            @Value("${cking.auth.refresh-cookie-secure}") boolean secure
    ) {
        this.refreshTokenTtl = refreshTokenTtl;
        this.secure = secure;
    }

    /** 지정 Web 세션의 새 opaque Token을 담은 HttpOnly same-site Cookie를 발급한다. */
    public ResponseCookie create(RefreshSessionType sessionType, String refreshToken) {
        return cookieBuilder(sessionType, refreshToken).maxAge(refreshTokenTtl).build();
    }

    /** 기존 사용자 Web 호출자의 호환을 위해 USER_WEB Refresh Cookie를 발급한다. */
    @Deprecated(forRemoval = false)
    public ResponseCookie create(String refreshToken) {
        return create(RefreshSessionType.USER_WEB, refreshToken);
    }

    /** 지정 Web 세션의 기존 Refresh Token Cookie만 즉시 만료한다. */
    public ResponseCookie expire(RefreshSessionType sessionType) {
        return cookieBuilder(sessionType, "").maxAge(Duration.ZERO).build();
    }

    /** 기존 사용자 Web 호출자의 호환을 위해 USER_WEB Refresh Cookie를 만료한다. */
    @Deprecated(forRemoval = false)
    public ResponseCookie expire() {
        return expire(RefreshSessionType.USER_WEB);
    }

    /** Refresh Cookie의 세션별 이름·경로와 공통 보안 속성을 설정한다. */
    private ResponseCookie.ResponseCookieBuilder cookieBuilder(RefreshSessionType sessionType, String value) {
        Objects.requireNonNull(sessionType, "sessionType은 필수입니다.");
        return ResponseCookie.from(cookieName(sessionType), value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(cookiePath(sessionType));
    }

    /** Web 세션 유형에 대응하는 Refresh Cookie 이름을 반환한다. */
    private String cookieName(RefreshSessionType sessionType) {
        return sessionType == RefreshSessionType.ADMIN_WEB ? ADMIN_COOKIE_NAME : USER_COOKIE_NAME;
    }

    /** Web 세션 유형이 다른 인증 endpoint에 Cookie를 보내지 않도록 Cookie Path를 반환한다. */
    private String cookiePath(RefreshSessionType sessionType) {
        return sessionType == RefreshSessionType.ADMIN_WEB ? ADMIN_COOKIE_PATH : USER_COOKIE_PATH;
    }
}

package kr.co.cking.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/** Refresh Cookie의 보안 속성과 만료 처리를 확인한다. */
class RefreshTokenCookieFactoryTest {

    /** 새 Cookie가 same-site Refresh Token 정책의 모든 속성을 갖는지 검증한다. */
    @Test
    void RefreshCookie는_HttpOnly_Lax_14일_TTL로_발급된다() {
        RefreshTokenCookieFactory factory = new RefreshTokenCookieFactory(Duration.ofDays(14), true);

        ResponseCookie cookie = factory.create("refresh-token");

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getValue()).isEqualTo("refresh-token");
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(14));
    }

    /** Logout용 Cookie가 같은 범위에서 즉시 만료되는지 검증한다. */
    @Test
    void 만료_Cookie는_기존_RefreshCookie를_덮어쓴다() {
        RefreshTokenCookieFactory factory = new RefreshTokenCookieFactory(Duration.ofDays(14), true);

        ResponseCookie cookie = factory.expire();

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
    }
}

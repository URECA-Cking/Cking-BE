package kr.co.cking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** JWT role Claim을 Spring Security 권한으로 변환하는 규칙을 검증한다. */
class JwtAuthenticationConverterConfigTest {

    private final JwtAuthenticationConverterConfig config = new JwtAuthenticationConverterConfig();

    /** USER와 ADMIN role을 각각 대응하는 ROLE_ 권한으로 변환하는지 검증한다. */
    @Test
    void JWT_role을_Spring_Security_권한으로_변환한다() {
        AbstractAuthenticationToken user = config.jwtAuthenticationConverter().convert(jwt("USER"));
        AbstractAuthenticationToken admin = config.jwtAuthenticationConverter().convert(jwt("ADMIN"));

        assertThat(user.getAuthorities()).extracting(Object::toString).contains("ROLE_USER");
        assertThat(admin.getAuthorities()).extracting(Object::toString).contains("ROLE_ADMIN");
    }

    /** 검증기에서 거절할 알 수 없는 role은 권한으로 승격하지 않는지 검증한다. */
    @Test
    void 알_수_없는_role은_권한으로_변환하지_않는다() {
        AbstractAuthenticationToken authentication = config.jwtAuthenticationConverter().convert(jwt("CREATOR"));

        assertThat(authentication.getAuthorities()).extracting(Object::toString).doesNotContain("ROLE_CREATOR");
    }

    /** 지정한 role Claim만 다른 최소 JWT를 만든다. */
    private Jwt jwt(String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("17")
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}

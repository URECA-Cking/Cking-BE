package kr.co.cking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import javax.crypto.SecretKey;

import kr.co.cking.common.security.AccessTokenJwtValidator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** HS256 Access JWT의 서명·Issuer·만료 검증 설정을 확인한다. */
class JwtConfigTest {

    private static final String SECRET = "2YNYNyIIJTSCD8zOXH/RpPp/Nm5+/n9gyVQpF5uRXlA=";
    private static final String OTHER_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private final JwtConfig jwtConfig = new JwtConfig();
    private final AccessTokenJwtValidator accessTokenJwtValidator = new AccessTokenJwtValidator();

    /** 같은 설정으로 서명한 올바른 Claim의 JWT를 Decoder가 읽는지 검증한다. */
    @Test
    void 올바른_서명_Issuer_Claim의_JWT를_검증한다() {
        JwtDecoder decoder = decoder();

        Jwt decoded = decoder.decode(issue("cking", Instant.now().plusSeconds(60), "USER"));

        assertThat(decoded.getSubject()).isEqualTo("17");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("USER");
    }

    /** 다른 Issuer·만료·role JWT를 Resource Server가 거절하는지 검증한다. */
    @Test
    void 유효하지_않은_Access_JWT를_거절한다() {
        JwtDecoder decoder = decoder();

        assertThatThrownBy(() -> decoder.decode(issue("other", Instant.now().plusSeconds(60), "USER")))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(issue("cking", Instant.now().minusSeconds(120), "USER")))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(issue("cking", Instant.now().plusSeconds(60), "CREATOR")))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(issue(otherSecretKey(), "cking", Instant.now().plusSeconds(60), "USER")))
                .isInstanceOf(JwtException.class);
    }

    /** 테스트용 Access JWT를 실제 Encoder로 서명한다. */
    private String issue(String issuer, Instant expiresAt, String role) {
        return issue(secretKey(), issuer, expiresAt, role);
    }

    /** 지정한 대칭 키로 테스트용 Access JWT를 서명한다. */
    private String issue(SecretKey signingKey, String issuer, Instant expiresAt, String role) {
        JwtEncoder encoder = jwtConfig.jwtEncoder(signingKey);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("17")
                .claim("role", role)
                .issuedAt(expiresAt.minusSeconds(60))
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** 같은 대칭 키와 Issuer를 사용하는 검증기를 만든다. */
    private JwtDecoder decoder() {
        return jwtConfig.jwtDecoder(secretKey(), "cking", accessTokenJwtValidator);
    }

    /** Base64 테스트 비밀값을 JWT 설정을 통해 키로 변환한다. */
    private SecretKey secretKey() {
        return jwtConfig.jwtSecretKey(SECRET);
    }

    /** 서명 불일치 검증에 사용할 다른 대칭 키를 만든다. */
    private SecretKey otherSecretKey() {
        return jwtConfig.jwtSecretKey(OTHER_SECRET);
    }
}

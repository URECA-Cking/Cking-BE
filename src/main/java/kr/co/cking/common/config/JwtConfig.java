package kr.co.cking.common.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import kr.co.cking.common.security.AccessTokenJwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;

/** HS256 Access JWT의 발급 키와 Resource Server 검증기를 구성한다. */
@Configuration
public class JwtConfig {

    /** Base64 환경 설정을 HS256 서명·검증에 공통으로 사용할 대칭 키로 변환한다. */
    @Bean
    public SecretKey jwtSecretKey(@Value("${cking.auth.jwt.secret}") String encodedSecret) {
        byte[] secret = Base64.getDecoder().decode(encodedSecret);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT 비밀값은 256비트 이상이어야 합니다.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    /** HS256으로 Access JWT를 서명할 Encoder를 만든다. */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /** HS256 서명·표준 시간 Claim·Issuer를 검증하는 Resource Server Decoder를 만든다. */
    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey,
            @Value("${cking.auth.jwt.issuer:cking}") String issuer,
            AccessTokenJwtValidator accessTokenJwtValidator
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), accessTokenJwtValidator));
        return decoder;
    }
}

package kr.co.cking.auth.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.member.domain.MemberRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** JWT 기반 Access Token 발급 구현의 Claim과 만료 정책을 검증한다. */
class JwtAccessTokenIssuerTest {

    private final JwtEncoder jwtEncoder = org.mockito.Mockito.mock(JwtEncoder.class);
    private final JwtAccessTokenIssuer accessTokenIssuer = new JwtAccessTokenIssuer(
            jwtEncoder, "cking", Duration.ofMinutes(30));

    /** Member ID·role·issuer·시간 Claim을 담은 30분 Bearer Token을 발급하는지 검증한다. */
    @Test
    void Access_JWT에_인증_Claim과_30분_TTL을_담는다() {
        when(jwtEncoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .build());

        AccessTokenResult response = accessTokenIssuer.issue(17L, MemberRole.USER);

        ArgumentCaptor<JwtEncoderParameters> parametersCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(parametersCaptor.capture());
        assertThat(response).isEqualTo(new AccessTokenResult("signed-token", "Bearer", 1800));
        Object issuer = parametersCaptor.getValue().getClaims().getClaim("iss");
        assertThat(issuer).isEqualTo("cking");
        assertThat(parametersCaptor.getValue().getClaims().getSubject()).isEqualTo("17");
        assertThat(parametersCaptor.getValue().getClaims().getClaimAsString("role")).isEqualTo("USER");
        assertThat(parametersCaptor.getValue().getClaims().getIssuedAt()).isNotNull();
        assertThat(parametersCaptor.getValue().getClaims().getExpiresAt())
                .isEqualTo(parametersCaptor.getValue().getClaims().getIssuedAt().plusSeconds(1800));
    }
}

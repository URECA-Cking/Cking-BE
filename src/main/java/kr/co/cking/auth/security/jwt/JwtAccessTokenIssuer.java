package kr.co.cking.auth.security.jwt;

import java.time.Duration;
import java.time.Instant;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.port.AccessTokenIssuer;
import kr.co.cking.member.domain.MemberRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

/** Spring Security JWT Encoder로 Cking Access Token을 발급한다. */
@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private static final String TOKEN_TYPE = "Bearer";

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final Duration accessTokenTtl;

    public JwtAccessTokenIssuer(
            JwtEncoder jwtEncoder,
            @Value("${cking.auth.jwt.issuer:cking}") String issuer,
            @Value("${cking.auth.jwt.access-token-ttl:PT30M}") Duration accessTokenTtl
    ) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    /** HS256 Header와 Access Token Claim으로 JWT를 서명한다. */
    @Override
    public AccessTokenResult issue(Long memberId, MemberRole role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(memberId.toString())
                .claim("role", role.name())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AccessTokenResult(accessToken, TOKEN_TYPE, accessTokenTtl.toSeconds());
    }
}

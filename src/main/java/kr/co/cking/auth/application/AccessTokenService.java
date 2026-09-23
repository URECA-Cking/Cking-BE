package kr.co.cking.auth.application;

import java.time.Duration;
import java.time.Instant;

import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

/** Login Code로 식별한 Member에게 짧은 수명의 Access JWT를 발급한다. */
@Service
@RequiredArgsConstructor
public class AccessTokenService {

    private static final String TOKEN_TYPE = "Bearer";

    private final MemberRepository memberRepository;
    private final JwtEncoder jwtEncoder;

    @Value("${cking.auth.jwt.issuer:cking}")
    private String issuer;

    @Value("${cking.auth.jwt.access-token-ttl:PT30M}")
    private Duration accessTokenTtl;

    /** 존재하는 Member의 역할을 Claim에 담아 Access JWT와 만료 초를 반환한다. */
    public AccessTokenResult issue(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_LOGIN_CODE));
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(member.getMemberId().toString())
                .claim("role", member.getRole().name())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AccessTokenResult(accessToken, TOKEN_TYPE, accessTokenTtl.toSeconds());
    }
}

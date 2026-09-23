package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

/** Access JWT 발급에 필요한 Claim과 Login Code 오류 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class AccessTokenServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private JwtEncoder jwtEncoder;
    @InjectMocks private AccessTokenService accessTokenService;

    /** Member ID·role·issuer·시간 Claim을 담은 30분 Bearer Token을 발급하는지 검증한다. */
    @Test
    void Access_JWT에_인증_Claim과_30분_TTL을_담는다() {
        Member member = new Member("홍길동", null, "user@example.com", MemberRole.USER);
        ReflectionTestUtils.setField(member, "memberId", 17L);
        ReflectionTestUtils.setField(accessTokenService, "issuer", "cking");
        ReflectionTestUtils.setField(accessTokenService, "accessTokenTtl", Duration.ofMinutes(30));
        when(memberRepository.findById(17L)).thenReturn(Optional.of(member));
        when(jwtEncoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .build());

        AccessTokenResult response = accessTokenService.issue(17L);

        ArgumentCaptor<JwtEncoderParameters> parametersCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        org.mockito.Mockito.verify(jwtEncoder).encode(parametersCaptor.capture());
        assertThat(response).isEqualTo(new AccessTokenResult("signed-token", "Bearer", 1800));
        Object issuer = parametersCaptor.getValue().getClaims().getClaim("iss");
        assertThat(issuer).isEqualTo("cking");
        assertThat(parametersCaptor.getValue().getClaims().getSubject()).isEqualTo("17");
        assertThat(parametersCaptor.getValue().getClaims().getClaimAsString("role")).isEqualTo("USER");
        assertThat(parametersCaptor.getValue().getClaims().getIssuedAt()).isNotNull();
        assertThat(parametersCaptor.getValue().getClaims().getExpiresAt())
                .isEqualTo(parametersCaptor.getValue().getClaims().getIssuedAt().plusSeconds(1800));
    }

    /** 소비된 Login Code의 Member가 없으면 유효하지 않은 Login Code로 처리하는지 검증한다. */
    @Test
    void 존재하지_않는_Member의_LoginCode는_유효하지_않다() {
        when(memberRepository.findById(17L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessTokenService.issue(17L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_LOGIN_CODE));
    }
}

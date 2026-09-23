package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import kr.co.cking.auth.application.dto.AccessTokenResult;
import kr.co.cking.auth.application.port.AccessTokenIssuer;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Access JWT 발급에 필요한 Claim과 인증 수단별 오류 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class AccessTokenServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private AccessTokenIssuer accessTokenIssuer;
    @InjectMocks private AccessTokenService accessTokenService;

    /** Member ID와 역할을 Access Token 발급 포트에 전달하는지 검증한다. */
    @Test
    void Member_정보로_AccessToken을_발급한다() {
        Member member = new Member("홍길동", null, "user@example.com", MemberRole.USER);
        ReflectionTestUtils.setField(member, "memberId", 17L);
        when(memberRepository.findById(17L)).thenReturn(Optional.of(member));
        AccessTokenResult issuedToken = new AccessTokenResult("signed-token", "Bearer", 1800);
        when(accessTokenIssuer.issue(17L, MemberRole.USER)).thenReturn(issuedToken);

        AccessTokenResult response = accessTokenService.issue(17L, AuthErrorCode.INVALID_LOGIN_CODE);

        assertThat(response).isEqualTo(issuedToken);
        verify(accessTokenIssuer).issue(17L, MemberRole.USER);
    }

    /** 소비된 Login Code의 Member가 없으면 유효하지 않은 Login Code로 처리하는지 검증한다. */
    @Test
    void 존재하지_않는_Member의_LoginCode는_유효하지_않다() {
        when(memberRepository.findById(17L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessTokenService.issue(17L, AuthErrorCode.INVALID_LOGIN_CODE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_LOGIN_CODE));
    }

    /** Refresh Token으로 식별한 Member가 없으면 Refresh Token 오류 계약을 사용한다. */
    @Test
    void 존재하지_않는_Member의_RefreshToken은_유효하지_않다() {
        when(memberRepository.findById(17L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessTokenService.issue(17L, AuthErrorCode.INVALID_REFRESH_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    /** 호출 맥락의 오류 코드는 회원 조회 전에 명시적으로 검증한다. */
    @Test
    void 인증수단_오류코드는_필수다() {
        assertThatThrownBy(() -> accessTokenService.issue(17L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("invalidCredentialError는 필수입니다.");
    }

    /** Member ID는 조회 전에 명시적으로 검증한다. */
    @Test
    void Member_ID는_필수다() {
        assertThatThrownBy(() -> accessTokenService.issue(null, AuthErrorCode.INVALID_LOGIN_CODE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("memberId는 필수입니다.");

        verifyNoInteractions(memberRepository);
    }
}

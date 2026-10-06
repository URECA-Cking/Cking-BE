package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import kr.co.cking.auth.application.port.AdminPasswordHasher;
import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.auth.repository.AdminAccountRepository;
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

/** 관리자 ID/PW 인증의 성공 경로와 외부 오류 통합 규칙을 검증한다. */
@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    @Mock private AdminAccountRepository adminAccountRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AdminPasswordHasher adminPasswordHasher;
    @InjectMocks private AdminAuthService adminAuthService;

    /** 활성 계정과 ADMIN Member가 일치하면 해당 Member ID를 반환하는지 검증한다. */
    @Test
    void 유효한_관리자_자격증명으로_Member를_인증한다() {
        AdminAccount adminAccount = adminAccount(17L, true);
        Member member = member(17L, MemberRole.ADMIN);
        when(adminAccountRepository.findByLoginId("admin")).thenReturn(Optional.of(adminAccount));
        when(adminPasswordHasher.matches("password", adminAccount.getPasswordHash())).thenReturn(true);
        when(memberRepository.findById(17L)).thenReturn(Optional.of(member));

        Long memberId = adminAuthService.authenticate("admin", "password");

        assertThat(memberId).isEqualTo(17L);
        verify(adminPasswordHasher).matches("password", adminAccount.getPasswordHash());
    }

    /** 존재하지 않는 로그인 ID도 자격 증명 오류로 통합하는지 검증한다. */
    @Test
    void 존재하지_않는_로그인ID는_자격증명_오류다() {
        when(adminAccountRepository.findByLoginId("missing")).thenReturn(Optional.empty());

        assertInvalidCredentials(() -> adminAuthService.authenticate("missing", "password"));

        verifyNoInteractions(adminPasswordHasher, memberRepository);
    }

    /** 틀린 비밀번호도 자격 증명 오류로 통합하는지 검증한다. */
    @Test
    void 틀린_비밀번호는_자격증명_오류다() {
        AdminAccount adminAccount = adminAccount(17L, true);
        when(adminAccountRepository.findByLoginId("admin")).thenReturn(Optional.of(adminAccount));
        when(adminPasswordHasher.matches("wrong-password", adminAccount.getPasswordHash())).thenReturn(false);

        assertInvalidCredentials(() -> adminAuthService.authenticate("admin", "wrong-password"));

        verifyNoInteractions(memberRepository);
    }

    /** 비활성 관리자 계정도 비밀번호 비교 없이 자격 증명 오류로 통합하는지 검증한다. */
    @Test
    void 비활성_계정은_자격증명_오류다() {
        when(adminAccountRepository.findByLoginId("admin")).thenReturn(Optional.of(adminAccount(17L, false)));

        assertInvalidCredentials(() -> adminAuthService.authenticate("admin", "password"));

        verifyNoInteractions(adminPasswordHasher, memberRepository);
    }

    /** 연결된 Member가 사라진 계정도 자격 증명 오류로 통합하는지 검증한다. */
    @Test
    void 연결_Member가_없으면_자격증명_오류다() {
        AdminAccount adminAccount = adminAccount(17L, true);
        when(adminAccountRepository.findByLoginId("admin")).thenReturn(Optional.of(adminAccount));
        when(adminPasswordHasher.matches("password", adminAccount.getPasswordHash())).thenReturn(true);
        when(memberRepository.findById(17L)).thenReturn(Optional.empty());

        assertInvalidCredentials(() -> adminAuthService.authenticate("admin", "password"));
    }

    /** USER 역할 Member에 연결된 계정도 자격 증명 오류로 통합하는지 검증한다. */
    @Test
    void USER_Member_연결은_자격증명_오류다() {
        AdminAccount adminAccount = adminAccount(17L, true);
        when(adminAccountRepository.findByLoginId("admin")).thenReturn(Optional.of(adminAccount));
        when(adminPasswordHasher.matches("password", adminAccount.getPasswordHash())).thenReturn(true);
        when(memberRepository.findById(17L)).thenReturn(Optional.of(member(17L, MemberRole.USER)));

        assertInvalidCredentials(() -> adminAuthService.authenticate("admin", "password"));
    }

    /** 테스트용 관리자 계정을 활성 여부와 함께 만든다. */
    private AdminAccount adminAccount(Long memberId, boolean active) {
        AdminAccount adminAccount = new AdminAccount(memberId, "admin", "$2a$10$valid.bcrypt.hash.value.for.test.account..............................................." );
        ReflectionTestUtils.setField(adminAccount, "active", active);
        return adminAccount;
    }

    /** 테스트용 Member에 영속화된 식별자를 부여한다. */
    private Member member(Long memberId, MemberRole role) {
        Member member = new Member("관리자", null, null, role);
        ReflectionTestUtils.setField(member, "memberId", memberId);
        return member;
    }

    /** 어떤 내부 실패 원인이든 관리자 자격 증명 오류로 응답하는지 검증한다. */
    private void assertInvalidCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.INVALID_ADMIN_CREDENTIALS));
    }
}

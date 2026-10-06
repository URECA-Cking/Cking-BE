package kr.co.cking.auth.application;

import kr.co.cking.auth.application.port.AdminPasswordHasher;
import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.auth.repository.AdminAccountRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 관리자 ID/PW 자격 증명을 검증하고 연결된 ADMIN Member를 식별한다. */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminAccountRepository adminAccountRepository;
    private final MemberRepository memberRepository;
    private final AdminPasswordHasher adminPasswordHasher;

    /** 로그인 ID와 비밀번호가 유효한 활성 관리자 계정의 Member ID를 반환한다. */
    public Long authenticate(String loginId, String password) {
        AdminAccount adminAccount = adminAccountRepository.findByLoginId(loginId)
                .orElseThrow(this::invalidCredentials);
        if (!adminAccount.isActive()
                || !adminPasswordHasher.matches(password, adminAccount.getPasswordHash())) {
            throw invalidCredentials();
        }

        Member member = memberRepository.findById(adminAccount.getMemberId())
                .orElseThrow(this::invalidCredentials);
        if (member.getRole() != MemberRole.ADMIN) {
            throw invalidCredentials();
        }
        return member.getMemberId();
    }

    /** 관리자 인증 실패의 내부 원인과 관계없이 동일한 외부 오류를 만든다. */
    private BusinessException invalidCredentials() {
        return new BusinessException(AuthErrorCode.INVALID_ADMIN_CREDENTIALS);
    }
}

package kr.co.cking.auth.application;

import kr.co.cking.auth.application.port.AdminPasswordHasher;
import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.auth.repository.AdminAccountRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개발·시연 환경의 관리자 계정과 연결 Member를 한 번만 준비한다. */
@Service
@RequiredArgsConstructor
public class AdminAccountInitializationService {

    private final AdminAccountRepository adminAccountRepository;
    private final MemberRepository memberRepository;
    private final AdminPasswordHasher adminPasswordHasher;

    /** 로그인 ID가 없을 때만 ADMIN Member와 BCrypt 자격 증명 계정을 함께 생성한다. */
    @Transactional
    public void initialize(String loginId, String rawPassword) {
        validateSeedCredentials(loginId, rawPassword);
        if (adminAccountRepository.existsByLoginId(loginId)) {
            return;
        }

        Member adminMember = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        adminAccountRepository.save(new AdminAccount(
                adminMember.getMemberId(),
                loginId,
                adminPasswordHasher.hash(rawPassword)
        ));
    }

    /** 초기 계정 설정이 비어 있어 비밀번호 없는 관리자가 만들어지는 일을 막는다. */
    private void validateSeedCredentials(String loginId, String rawPassword) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalStateException("관리자 초기 로그인 ID를 설정해야 합니다.");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException("관리자 초기 비밀번호를 환경변수로 설정해야 합니다.");
        }
    }
}

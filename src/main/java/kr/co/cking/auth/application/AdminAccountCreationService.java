package kr.co.cking.auth.application;

import kr.co.cking.auth.application.port.AdminPasswordHasher;
import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.auth.repository.AdminAccountRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 Member와 자격 증명 계정을 하나의 독립 트랜잭션으로 생성한다. */
@Service
@RequiredArgsConstructor
class AdminAccountCreationService {

    private final AdminAccountRepository adminAccountRepository;
    private final MemberRepository memberRepository;
    private final AdminPasswordHasher adminPasswordHasher;

    /** ADMIN Member와 BCrypt 자격 증명을 함께 저장하고, 실패하면 둘 다 롤백한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(String loginId, String rawPassword) {
        Member adminMember = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        adminAccountRepository.saveAndFlush(new AdminAccount(
                adminMember.getMemberId(),
                loginId,
                adminPasswordHasher.hash(rawPassword)
        ));
    }
}

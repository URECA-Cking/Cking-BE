package kr.co.cking.auth.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminAccountRepositoryJpaTest {

    @Autowired private AdminAccountRepository adminAccountRepository;
    @Autowired private MemberRepository memberRepository;

    /** 같은 login_id로 두 관리자 계정을 저장할 수 없는지 검증한다. */
    @Test
    void loginIdMustBeUnique() {
        Member firstAdmin = memberRepository.saveAndFlush(new Member("첫 관리자", null, null, MemberRole.ADMIN));
        Member secondAdmin = memberRepository.saveAndFlush(new Member("둘째 관리자", null, null, MemberRole.ADMIN));
        adminAccountRepository.saveAndFlush(new AdminAccount(firstAdmin.getMemberId(), "admin", "$2a$10$firstPasswordHash"));

        assertThatThrownBy(() -> adminAccountRepository.saveAndFlush(
                new AdminAccount(secondAdmin.getMemberId(), "admin", "$2a$10$secondPasswordHash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

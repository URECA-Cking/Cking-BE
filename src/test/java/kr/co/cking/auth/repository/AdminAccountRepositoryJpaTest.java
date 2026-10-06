package kr.co.cking.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.util.UUID;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminAccountRepositoryJpaTest {

    @Autowired private AdminAccountRepository adminAccountRepository;
    @Autowired private MemberRepository memberRepository;

    /** login_id로 관리자 계정 존재 여부를 정확히 조회하는지 검증한다. */
    @Test
    void checksAccountExistenceByLoginId() {
        Member member = memberRepository.saveAndFlush(new Member("조회 관리자", null, null, MemberRole.ADMIN));
        String loginId = "admin-" + UUID.randomUUID();
        adminAccountRepository.saveAndFlush(new AdminAccount(member.getMemberId(), loginId, "$2a$10$storedPasswordHash"));

        assertThat(adminAccountRepository.existsByLoginId(loginId)).isTrue();
        assertThat(adminAccountRepository.existsByLoginId("absent-" + UUID.randomUUID())).isFalse();
    }

    /** 같은 login_id로 두 관리자 계정을 저장할 수 없는지 검증한다. */
    @Test
    void loginIdMustBeUnique() {
        Member firstAdmin = memberRepository.saveAndFlush(new Member("첫 관리자", null, null, MemberRole.ADMIN));
        Member secondAdmin = memberRepository.saveAndFlush(new Member("둘째 관리자", null, null, MemberRole.ADMIN));
        String loginId = "admin-" + UUID.randomUUID();
        adminAccountRepository.saveAndFlush(new AdminAccount(firstAdmin.getMemberId(), loginId, "$2a$10$firstPasswordHash"));

        assertThatThrownBy(() -> adminAccountRepository.saveAndFlush(
                new AdminAccount(secondAdmin.getMemberId(), loginId, "$2a$10$secondPasswordHash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 하나의 Member에 두 관리자 계정을 연결할 수 없는지 검증한다. */
    @Test
    void memberIdMustBeUnique() {
        Member member = memberRepository.saveAndFlush(new Member("단일 계정 관리자", null, null, MemberRole.ADMIN));
        adminAccountRepository.saveAndFlush(new AdminAccount(
                member.getMemberId(), "admin-" + UUID.randomUUID(), "$2a$10$firstPasswordHash"));

        assertThatThrownBy(() -> adminAccountRepository.saveAndFlush(new AdminAccount(
                member.getMemberId(), "admin-" + UUID.randomUUID(), "$2a$10$secondPasswordHash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 관리자 계정이 참조하는 Member를 삭제할 수 없는지 검증한다. */
    @Test
    void memberReferencedByAdminAccountCannotBeDeleted() {
        Member member = memberRepository.saveAndFlush(new Member("연결 관리자", null, null, MemberRole.ADMIN));
        adminAccountRepository.saveAndFlush(new AdminAccount(
                member.getMemberId(), "admin-" + UUID.randomUUID(), "$2a$10$storedPasswordHash"));

        memberRepository.delete(member);

        assertThatThrownBy(memberRepository::flush)
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

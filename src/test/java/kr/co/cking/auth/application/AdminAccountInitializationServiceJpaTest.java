package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.auth.domain.AdminAccount;
import kr.co.cking.auth.repository.AdminAccountRepository;
import kr.co.cking.auth.security.password.BCryptAdminPasswordHasher;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.UUID;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminAccountInitializationServiceJpaTest {

    @Autowired private AdminAccountRepository adminAccountRepository;
    @Autowired private MemberRepository memberRepository;

    private AdminAccountInitializationService initializationService;
    private String loginId;
    private String rawPassword;

    /** 실제 Repository와 BCrypt 해셔를 사용해 초기화 서비스를 준비한다. */
    @BeforeEach
    void setUp() {
        loginId = "admin-" + UUID.randomUUID();
        rawPassword = UUID.randomUUID().toString();
        initializationService = new AdminAccountInitializationService(
                adminAccountRepository,
                memberRepository,
                new BCryptAdminPasswordHasher()
        );
    }

    /** 최초 초기화가 ADMIN Member와 BCrypt 자격 증명 계정을 함께 만드는지 검증한다. */
    @Test
    void createsAdminMemberAndAccountWithBcryptPasswordHash() {
        initializationService.initialize(loginId, rawPassword);
        adminAccountRepository.flush();

        AdminAccount account = adminAccountRepository.findByLoginId(loginId).orElseThrow();
        Member member = memberRepository.findById(account.getMemberId()).orElseThrow();

        assertThat(member.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(account.getLoginId()).isEqualTo(loginId);
        assertThat(account.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(new BCryptPasswordEncoder().matches(rawPassword, account.getPasswordHash())).isTrue();
    }

    /** 같은 로그인 ID로 초기화를 반복해도 Member와 관리자 계정을 하나만 유지하는지 검증한다. */
    @Test
    void doesNotCreateDuplicateAccountWhenLoginIdAlreadyExists() {
        long accountCountBefore = adminAccountRepository.count();
        long memberCountBefore = memberRepository.count();

        initializationService.initialize(loginId, rawPassword);
        initializationService.initialize(loginId, rawPassword);
        adminAccountRepository.flush();

        assertThat(adminAccountRepository.count()).isEqualTo(accountCountBefore + 1);
        assertThat(memberRepository.count()).isEqualTo(memberCountBefore + 1);
    }

    /** 기존 계정이 있으면 시딩 비밀번호 없이 재기동해도 새 계정을 만들지 않는지 검증한다. */
    @Test
    void skipsPasswordValidationWhenAccountAlreadyExists() {
        initializationService.initialize(loginId, rawPassword);
        long accountCountAfterCreation = adminAccountRepository.count();
        long memberCountAfterCreation = memberRepository.count();

        initializationService.initialize(loginId, " ");

        assertThat(adminAccountRepository.count()).isEqualTo(accountCountAfterCreation);
        assertThat(memberRepository.count()).isEqualTo(memberCountAfterCreation);
    }

    /** 빈 로그인 ID로 초기화를 요청하면 계정 생성 전에 실패하는지 검증한다. */
    @Test
    void rejectsBlankLoginId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> initializationService.initialize(" ", rawPassword))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("관리자 초기 로그인 ID를 설정해야 합니다.");
    }

    /** 새 계정을 만들 때 비밀번호가 비어 있으면 초기화가 실패하는지 검증한다. */
    @Test
    void rejectsBlankPasswordWhenAccountDoesNotExist() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> initializationService.initialize(loginId, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("관리자 초기 비밀번호를 환경변수로 설정해야 합니다.");
    }
}

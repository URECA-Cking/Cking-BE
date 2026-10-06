package kr.co.cking.auth.repository;

import kr.co.cking.auth.domain.AdminAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    /** 같은 로그인 ID의 관리자 계정이 이미 초기화됐는지 확인한다. */
    boolean existsByLoginId(String loginId);

    /** 로그인 ID로 관리자 계정을 조회한다. */
    Optional<AdminAccount> findByLoginId(String loginId);
}

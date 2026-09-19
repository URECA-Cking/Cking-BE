package kr.co.cking.winner.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import kr.co.cking.winner.domain.WinnerManagement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WinnerManagementRepository extends JpaRepository<WinnerManagement, Long> {

    Optional<WinnerManagement> findByWinnerId(Long winnerId);

    /** 같은 Winner의 상태 명령을 직렬화하기 위해 운영 정보를 쓰기 잠금으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wm from WinnerManagement wm where wm.winnerId = :winnerId")
    Optional<WinnerManagement> findByWinnerIdForUpdate(@Param("winnerId") Long winnerId);
}

package kr.co.cking.winner.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.domain.WinnerManagementStatus;
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

    /**
     * Event 행 잠금을 잡은 보관 명령에서 수령 대기 Winner를 최신 커밋 기준으로 확인한다.
     * 일반 집계 조회는 REPEATABLE READ 스냅샷을 볼 수 있으므로 현재 읽기인 비관적 잠금을 사용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select wm
            from WinnerManagement wm
            join kr.co.cking.winner.domain.Winner winner on winner.id = wm.winnerId
            where winner.eventId = :eventId and wm.status = :status
            """)
    List<WinnerManagement> findAllByEventIdAndStatusForUpdate(@Param("eventId") Long eventId,
                                                               @Param("status") WinnerManagementStatus status);
}

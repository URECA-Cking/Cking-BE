package kr.co.cking.winner.repository;

import java.util.List;
import kr.co.cking.winner.domain.WinnerStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** Winner 상태 변경 이력을 저장하고 조회하는 Repository다. */
public interface WinnerStatusHistoryRepository extends JpaRepository<WinnerStatusHistory, Long> {

    /** 특정 WinnerManagement의 상태 이력을 변경 시각과 이력 식별자 오름차순으로 조회한다. */
    List<WinnerStatusHistory> findByWinnerManagementIdOrderByCreatedAtAscIdAsc(Long winnerManagementId);
}

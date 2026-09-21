package kr.co.cking.winner.repository;

import kr.co.cking.winner.domain.WinnerStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** Winner 상태 변경 이력을 저장하고 조회하는 Repository다. */
public interface WinnerStatusHistoryRepository extends JpaRepository<WinnerStatusHistory, Long> {
}

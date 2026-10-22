package kr.co.cking.redraw.repository;

import kr.co.cking.redraw.domain.RedrawExecutionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/** Redraw 실행 결과 이력을 저장한다. */
public interface RedrawExecutionHistoryRepository extends JpaRepository<RedrawExecutionHistory, Long> {
}

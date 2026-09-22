package kr.co.cking.drawing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.DrawAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DrawAttemptHistoryRepository extends JpaRepository<DrawAttemptHistory, Long> {

    Optional<DrawAttemptHistory> findByDrawingIdAndAttemptNo(Long drawingId, int attemptNo);

    Optional<DrawAttemptHistory> findFirstByDrawingIdOrderByAttemptNoDesc(Long drawingId);

    @Query("""
            select h.drawingId
            from DrawAttemptHistory h
            join kr.co.cking.drawing.domain.Drawing d on d.id = h.drawingId
            where h.status = :status
              and h.startedAt < :cutoff
              and d.status = kr.co.cking.drawing.domain.DrawingStatus.RUNNING
            order by h.startedAt asc
            """)
    List<Long> findStaleRunningDrawingIds(
            @Param("status") DrawAttemptStatus status,
            @Param("cutoff") Instant cutoff
    );
}

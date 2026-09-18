package kr.co.cking.winner.repository;

import java.util.List;
import kr.co.cking.winner.domain.Winner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WinnerRepository extends JpaRepository<Winner, Long> {

    List<Winner> findAllByDrawingIdOrderByRankInDrawingAsc(Long drawingId);

    /** Event의 공개·완료 Drawing에 속한 Winner만 추첨 회차와 순위 순으로 조회한다. */
    @Query("""
            select w from Winner w
            join kr.co.cking.drawing.domain.Drawing d on d.id = w.drawingId
            where w.eventId = :eventId
              and d.visibility = kr.co.cking.drawing.domain.DrawingVisibility.PUBLIC
              and d.status = kr.co.cking.drawing.domain.DrawingStatus.COMPLETED
            order by d.drawNo asc, w.rankInDrawing asc
            """)
    List<Winner> findAllPublicByEventIdOrderByDrawNoAndRank(@Param("eventId") Long eventId);

    boolean existsByEventIdAndMemberId(Long eventId, Long memberId);
}

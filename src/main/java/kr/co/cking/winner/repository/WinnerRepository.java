package kr.co.cking.winner.repository;

import java.util.List;
import kr.co.cking.winner.domain.Winner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WinnerRepository extends JpaRepository<Winner, Long> {

    List<Winner> findAllByDrawingIdOrderByRankInDrawingAsc(Long drawingId);

    /**
     * Event의 공개·완료 Drawing에 속한 Winner만 추첨 회차와 순위 순으로 조회한다.
     *
     * <p>공개 범위 필터와 회차 정렬을 DB에서 한 번에 수행하기 위해 Drawing을 읽기 전용으로 join한다.
     * Drawing Entity는 반환하지 않고 {@link PublicWinnerProjection}만 반환하며, 이 조회 경로에서는
     * Drawing 상태를 변경하지 않는다. Drawing 상태 변경은 계속 Drawing 도메인의 Service만 담당한다.
     */
    @Query("""
            select new kr.co.cking.winner.repository.PublicWinnerProjection(
                w.id, w.drawingId, d.drawNo, d.drawType, w.memberId, w.rankInDrawing,
                w.prizeKey, w.prizeDisplayName, w.prizePriority
            )
            from Winner w
            join kr.co.cking.drawing.domain.Drawing d on d.id = w.drawingId
            where w.eventId = :eventId
              and d.visibility = kr.co.cking.drawing.domain.DrawingVisibility.PUBLIC
              and d.status = kr.co.cking.drawing.domain.DrawingStatus.COMPLETED
            order by d.drawNo asc, w.rankInDrawing asc
            """)
    List<PublicWinnerProjection> findAllPublicByEventIdOrderByDrawNoAndRank(@Param("eventId") Long eventId);

    /** 공개 완료된 Drawing 중 특정 Member가 당첨된 Winner와 현재 운영 상태를 추첨 회차 순으로 읽기 전용 조회한다. */
    @Query("""
            select new kr.co.cking.winner.repository.MyWinnerProjection(
                w.id, w.eventId, w.drawingId, d.drawNo, d.drawType, w.rankInDrawing,
                w.appliedTicketCount, w.createdAt, wm.id, wm.status, wm.createdAt, wm.updatedAt
            )
            from Winner w
            join kr.co.cking.drawing.domain.Drawing d on d.id = w.drawingId
            join kr.co.cking.winner.domain.WinnerManagement wm on wm.winnerId = w.id
            where w.memberId = :memberId
              and d.visibility = kr.co.cking.drawing.domain.DrawingVisibility.PUBLIC
              and d.status = kr.co.cking.drawing.domain.DrawingStatus.COMPLETED
            order by d.drawNo asc, w.rankInDrawing asc, w.eventId asc
            """)
    List<MyWinnerProjection> findAllWithManagementByMemberIdOrderByDrawNoRankAndEventId(
            @Param("memberId") Long memberId
    );

    boolean existsByEventIdAndMemberId(Long eventId, Long memberId);
}

package kr.co.cking.redraw.repository;

import java.util.List;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 원본 INITIAL Drawing에서 현재 재추첨 가능한 Winner 결원만 읽는 조회 경계다. */
public interface RedrawVacancyCandidateRepository extends Repository<Winner, Long> {

    /** DECLINED 또는 DISQUALIFIED 상태인 원본 Drawing Winner ID를 정렬해 반환한다. */
    @Query("""
            select winner.id
            from kr.co.cking.winner.domain.Winner winner
            join kr.co.cking.winner.domain.WinnerManagement management on management.winnerId = winner.id
            where winner.eventId = :eventId
              and winner.drawingId = :originalDrawingId
              and management.status in :vacancyStatuses
            order by winner.id asc
            """)
    List<Long> findVacancyWinnerIds(
            @Param("eventId") Long eventId,
            @Param("originalDrawingId") Long originalDrawingId,
            @Param("vacancyStatuses") List<WinnerManagementStatus> vacancyStatuses
    );
}

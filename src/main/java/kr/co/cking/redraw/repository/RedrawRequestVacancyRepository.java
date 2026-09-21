package kr.co.cking.redraw.repository;

import java.util.Collection;
import java.util.List;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** RedrawRequest가 점유한 결원의 저장과 진행 중 점유 조회를 담당한다. */
public interface RedrawRequestVacancyRepository extends JpaRepository<RedrawRequestVacancy, Long> {

    /** 진행 중 REQUESTED·APPROVED 요청이 이미 점유한 Winner ID만 조회한다. */
    @Query("""
            select vacancy.winnerId
            from RedrawRequestVacancy vacancy
            join RedrawRequest request on request.id = vacancy.redrawRequestId
            where vacancy.winnerId in :winnerIds
              and request.status in (
                  kr.co.cking.redraw.domain.RedrawRequestStatus.REQUESTED,
                  kr.co.cking.redraw.domain.RedrawRequestStatus.APPROVED
              )
              and request.executionStatus = kr.co.cking.redraw.domain.RedrawExecutionStatus.PENDING
            """)
    List<Long> findOccupiedWinnerIdsInProgress(@Param("winnerIds") Collection<Long> winnerIds);
}

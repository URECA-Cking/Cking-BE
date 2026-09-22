package kr.co.cking.redraw.repository;

import java.util.Collection;
import java.util.List;
import kr.co.cking.redraw.domain.RedrawRequestVacancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** RedrawRequest가 점유한 결원의 저장과 임시·영구 점유 조회를 담당한다. */
public interface RedrawRequestVacancyRepository extends JpaRepository<RedrawRequestVacancy, Long> {

    /** 특정 요청이 생성 시점에 확정한 결원을 생성 순서대로 조회한다. */
    List<RedrawRequestVacancy> findAllByRedrawRequestIdOrderByIdAsc(Long redrawRequestId);

    /** 진행 중 요청과 실행 완료 요청이 점유한 Winner ID를 조회한다. */
    @Query("""
            select vacancy.winnerId
            from RedrawRequestVacancy vacancy
            join RedrawRequest request on request.id = vacancy.redrawRequestId
            where vacancy.winnerId in :winnerIds
              and (
                  (
                      request.status in (
                          kr.co.cking.redraw.domain.RedrawRequestStatus.REQUESTED,
                          kr.co.cking.redraw.domain.RedrawRequestStatus.APPROVED
                      )
                      and request.executionStatus = kr.co.cking.redraw.domain.RedrawExecutionStatus.PENDING
                  )
                  or request.executionStatus = kr.co.cking.redraw.domain.RedrawExecutionStatus.EXECUTED
              )
            """)
    List<Long> findOccupiedWinnerIds(@Param("winnerIds") Collection<Long> winnerIds);
}

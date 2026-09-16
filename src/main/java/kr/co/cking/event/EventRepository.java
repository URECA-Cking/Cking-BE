package kr.co.cking.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByDeletedAtIsNull(Pageable pageable);

    /**
     * displayStatus는 저장 컬럼이 아니라 status+시각 조합으로 계산되는 값이라(API 명세 §4.1)
     * 목록 필터도 그 조합 조건으로 짠다. creatorId/displayStatus는 null이면 필터 안 함.
     *
     * <p>DRAFT/PENDING_APPROVAL/REJECTED는 displayStatus 매핑 대상이 아니라서(§4.1) 필터 여부와
     * 무관하게 항상 제외한다 — 포함되면 EventSummary 변환 시 DisplayStatus.of()가 예외를 던진다.
     */
    @Query("""
            select e from Event e
            where e.deletedAt is null
              and e.status not in (
                  kr.co.cking.event.EventStatus.DRAFT,
                  kr.co.cking.event.EventStatus.PENDING_APPROVAL,
                  kr.co.cking.event.EventStatus.REJECTED
              )
              and (:creatorId is null or e.creatorId = :creatorId)
              and (
                :displayStatus is null
                or (:displayStatus = 'UPCOMING' and e.status = kr.co.cking.event.EventStatus.SCHEDULED)
                or (:displayStatus = 'IN_PROGRESS'
                    and e.status = kr.co.cking.event.EventStatus.OPEN and e.endAt > :now)
                or (:displayStatus = 'CLOSED'
                    and (
                        (e.status = kr.co.cking.event.EventStatus.OPEN and e.endAt <= :now)
                        or e.status in (
                            kr.co.cking.event.EventStatus.CLOSING,
                            kr.co.cking.event.EventStatus.CLOSED,
                            kr.co.cking.event.EventStatus.DRAW_COMPLETED,
                            kr.co.cking.event.EventStatus.PUBLISHED
                        )
                    ))
              )
            """)
    Page<Event> search(@Param("creatorId") Long creatorId,
                        @Param("displayStatus") String displayStatus,
                        @Param("now") Instant now,
                        Pageable pageable);

    default Page<Event> search(Long creatorId, DisplayStatus displayStatus, Instant now, Pageable pageable) {
        return search(creatorId, displayStatus == null ? null : displayStatus.name(), now, pageable);
    }
}

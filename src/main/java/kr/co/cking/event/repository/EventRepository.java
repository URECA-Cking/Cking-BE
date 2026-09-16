package kr.co.cking.event.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByDeletedAtIsNull(Pageable pageable);

    Optional<Event> findByRequestId(String requestId);

    /** 상태 전이를 직렬화해야 하는 명령 경로(승인·거절·마감 등) 전용 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Event> findByEventId(Long eventId);

    List<Event> findByEventIdIn(Collection<Long> eventIds);

    Page<Event> findByCreatorIdAndDeletedAtIsNullOrderByCreatedAtDescEventIdDesc(Long creatorId, Pageable pageable);

    /** EventLifecycleScheduler가 자동 마감 대상(OPEN이고 endAt이 지난 이벤트)을 찾을 때 쓴다. */
    List<Event> findByStatusAndEndAtLessThanEqual(EventStatus status, Instant endAt);

    /** 서버 재기동 후 Drain이 끝나지 않은 CLOSING 이벤트를 재개할 때 쓴다. */
    List<Event> findByStatus(EventStatus status);

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
                  kr.co.cking.event.domain.EventStatus.DRAFT,
                  kr.co.cking.event.domain.EventStatus.PENDING_APPROVAL,
                  kr.co.cking.event.domain.EventStatus.REJECTED
              )
              and (:creatorId is null or e.creatorId = :creatorId)
              and (
                :displayStatus is null
                or (:displayStatus = 'UPCOMING' and e.status = kr.co.cking.event.domain.EventStatus.SCHEDULED)
                or (:displayStatus = 'IN_PROGRESS'
                    and e.status = kr.co.cking.event.domain.EventStatus.OPEN and e.endAt > :now)
                or (:displayStatus = 'CLOSED'
                    and (
                        (e.status = kr.co.cking.event.domain.EventStatus.OPEN and e.endAt <= :now)
                        or e.status in (
                            kr.co.cking.event.domain.EventStatus.CLOSING,
                            kr.co.cking.event.domain.EventStatus.CLOSED,
                            kr.co.cking.event.domain.EventStatus.DRAW_COMPLETED,
                            kr.co.cking.event.domain.EventStatus.PUBLISHED
                        )
                    ))
              )
            """)
    Page<Event> search(
            @Param("creatorId") Long creatorId,
            @Param("displayStatus") String displayStatus,
            @Param("now") Instant now,
            Pageable pageable
    );

    default Page<Event> search(Long creatorId, DisplayStatus displayStatus, Instant now, Pageable pageable) {
        return search(creatorId, displayStatus == null ? null : displayStatus.name(), now, pageable);
    }
}

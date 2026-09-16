package kr.co.cking.event.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
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

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByDeletedAtIsNull(Pageable pageable);

    Optional<Event> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Event> findByEventId(Long eventId);

    List<Event> findByEventIdIn(Collection<Long> eventIds);

    Page<Event> findByCreatorIdAndDeletedAtIsNullOrderByCreatedAtDescEventIdDesc(Long creatorId, Pageable pageable);

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

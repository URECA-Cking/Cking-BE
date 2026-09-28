package kr.co.cking.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import kr.co.cking.event.domain.EventEntry;

public interface EventEntryRepository extends JpaRepository<EventEntry, Long> {

    Optional<EventEntry> findByRequestId(String requestId);

    // 실시간 응모 현황(FR-P2-045~050): Gate 최초 적재 시 이 값으로 Redis 집계 키를 초기화하고,
    // 집계 키가 없을 때(realtime=false) 조회를 대체한다. member_id 순서는 결과에 영향을 주지
    // 않으므로 정렬을 강제하지 않는다.
    @Query("""
            select e.memberId as memberId, sum(e.usedTicketCount) as ticketCount
            from EventEntry e
            where e.eventId = :eventId
            group by e.memberId
            """)
    List<EventEntryAggregate> aggregateByEvent(@Param("eventId") Long eventId);

    @Query("""
            select e.entryId as entryId, e.usedTicketCount as usedTicketCount, e.appliedAt as appliedAt,
                   case when exists (select 1 from CommonTicketLedger l where l.eventEntryId = e.entryId)
                        then true else false end as common
            from EventEntry e
            where e.memberId = :memberId and e.eventId = :eventId
            order by e.appliedAt desc, e.entryId desc
            """)
    List<EventEntryView> findFirstPageView(@Param("memberId") Long memberId,
                                           @Param("eventId") Long eventId,
                                           Pageable pageable);

    @Query("""
            select e.entryId as entryId, e.usedTicketCount as usedTicketCount, e.appliedAt as appliedAt,
                   case when exists (select 1 from CommonTicketLedger l where l.eventEntryId = e.entryId)
                        then true else false end as common
            from EventEntry e
            where e.memberId = :memberId and e.eventId = :eventId
              and (e.appliedAt < :appliedAt
                   or (e.appliedAt = :appliedAt and e.entryId < :entryId))
            order by e.appliedAt desc, e.entryId desc
            """)
    List<EventEntryView> findAfterCursorView(@Param("memberId") Long memberId,
                                             @Param("eventId") Long eventId,
                                             @Param("appliedAt") Instant appliedAt,
                                             @Param("entryId") Long entryId,
                                             Pageable pageable);
}

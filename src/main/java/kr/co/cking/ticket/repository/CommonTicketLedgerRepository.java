package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.CommonTicketLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommonTicketLedgerRepository extends JpaRepository<CommonTicketLedger, Long> {

    Optional<CommonTicketLedger> findByRequestId(String requestId);

    @Query(value = """
            select l.ledger_id as ledgerId, l.delta_amount as deltaAmount, l.type as type,
                   mc.mission_id as missionId, ee.event_id as eventId,
                   l.reason as reason, l.request_id as requestId, l.created_at as createdAt
            from common_ticket_ledger l
            left join common_mission_completion mc on mc.completion_id = l.mission_completion_id
            left join event_entry ee on ee.entry_id = l.event_entry_id
            where l.member_id = :memberId
            order by l.created_at desc, l.ledger_id desc
            """, nativeQuery = true)
    List<CommonTicketLedgerView> findFirstPageView(@Param("memberId") Long memberId, Pageable pageable);

    @Query(value = """
            select l.ledger_id as ledgerId, l.delta_amount as deltaAmount, l.type as type,
                   mc.mission_id as missionId, ee.event_id as eventId,
                   l.reason as reason, l.request_id as requestId, l.created_at as createdAt
            from common_ticket_ledger l
            left join common_mission_completion mc on mc.completion_id = l.mission_completion_id
            left join event_entry ee on ee.entry_id = l.event_entry_id
            where l.member_id = :memberId
              and (l.created_at < :createdAt
                   or (l.created_at = :createdAt and l.ledger_id < :ledgerId))
            order by l.created_at desc, l.ledger_id desc
            """, nativeQuery = true)
    List<CommonTicketLedgerView> findAfterCursorView(@Param("memberId") Long memberId,
                                                     @Param("createdAt") Instant createdAt,
                                                     @Param("ledgerId") Long ledgerId,
                                                     Pageable pageable);
}

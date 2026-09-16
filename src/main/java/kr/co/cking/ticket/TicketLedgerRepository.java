package kr.co.cking.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketLedgerRepository extends JpaRepository<TicketLedger, Long> {

    Optional<TicketLedger> findByRequestId(String requestId);
}

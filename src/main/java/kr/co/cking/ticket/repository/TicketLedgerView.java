package kr.co.cking.ticket.repository;

import java.time.Instant;

public interface TicketLedgerView {
    Long getLedgerId();
    Long getDeltaAmount();
    String getType();
    Long getMissionId();
    Long getEventId();
    String getReason();
    String getRequestId();
    Instant getCreatedAt();
}

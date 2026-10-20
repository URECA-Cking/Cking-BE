package kr.co.cking.ticket.repository;

import java.time.Instant;

public interface CommonTicketLedgerView {
    Long getLedgerId();
    Long getDeltaAmount();
    String getType();
    Long getMissionId();
    String getMissionType();
    Long getEventId();
    String getEventTitle();
    String getReason();
    String getRequestId();
    Instant getCreatedAt();
}

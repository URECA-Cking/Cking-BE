package kr.co.cking.ticket;

import java.time.Instant;

public record TicketLedgerItemResponse(
        Long ledgerId,
        Long deltaAmount,
        String type,
        Long missionId,
        Long eventId,
        String reason,
        String requestId,
        Instant createdAt
) {

    public static TicketLedgerItemResponse from(TicketLedgerView ledger) {
        return new TicketLedgerItemResponse(
                ledger.getLedgerId(), ledger.getDeltaAmount(), ledger.getType(),
                ledger.getMissionId(), ledger.getEventId(), ledger.getReason(), ledger.getRequestId(), ledger.getCreatedAt());
    }
}

package kr.co.cking.ticket.application.dto;

import java.time.Instant;

import kr.co.cking.ticket.repository.CommonTicketLedgerView;

public record CommonTicketLedgerItemResponse(
        Long ledgerId,
        Long deltaAmount,
        String type,
        Long missionId,
        Long eventId,
        String reason,
        String requestId,
        Instant createdAt
) {

    public static CommonTicketLedgerItemResponse from(CommonTicketLedgerView ledger) {
        return new CommonTicketLedgerItemResponse(
                ledger.getLedgerId(), ledger.getDeltaAmount(), ledger.getType(),
                ledger.getMissionId(), ledger.getEventId(), ledger.getReason(), ledger.getRequestId(), ledger.getCreatedAt());
    }
}

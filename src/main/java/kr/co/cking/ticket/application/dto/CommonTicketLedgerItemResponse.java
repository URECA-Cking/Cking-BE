package kr.co.cking.ticket.application.dto;

import java.time.Instant;

import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.repository.CommonTicketLedgerView;

public record CommonTicketLedgerItemResponse(
        Long ledgerId,
        Long deltaAmount,
        String type,
        Long missionId,
        String missionName,
        Long eventId,
        String eventTitle,
        String reason,
        String requestId,
        Instant createdAt
) {

    public static CommonTicketLedgerItemResponse from(CommonTicketLedgerView ledger) {
        return new CommonTicketLedgerItemResponse(
                ledger.getLedgerId(), ledger.getDeltaAmount(), ledger.getType(),
                ledger.getMissionId(), MissionType.displayNameOf(ledger.getMissionType()),
                ledger.getEventId(), ledger.getEventTitle(), ledger.getReason(), ledger.getRequestId(), ledger.getCreatedAt());
    }
}

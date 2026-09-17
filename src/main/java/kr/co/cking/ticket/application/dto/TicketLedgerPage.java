package kr.co.cking.ticket.application.dto;

import java.util.List;

public record TicketLedgerPage(Long userId, Long creatorId, List<TicketLedgerItemResponse> items,
                               String nextCursor, boolean hasNext) {
}

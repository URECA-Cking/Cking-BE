package kr.co.cking.ticket.application.dto;

import java.util.List;

public record CommonTicketLedgerPage(Long userId, List<CommonTicketLedgerItemResponse> items,
                                     String nextCursor, boolean hasNext) {
}

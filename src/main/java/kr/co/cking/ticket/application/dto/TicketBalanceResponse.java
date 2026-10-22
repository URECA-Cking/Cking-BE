package kr.co.cking.ticket.application.dto;

import java.time.Instant;

public record TicketBalanceResponse(Long userId, Long creatorId, long balance, Instant updatedAt) {
}

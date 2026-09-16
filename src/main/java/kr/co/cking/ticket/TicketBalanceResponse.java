package kr.co.cking.ticket;

import java.time.Instant;

public record TicketBalanceResponse(Long userId, Long creatorId, long balance, Instant updatedAt) {
}

package kr.co.cking.ticket.application.dto;

import java.time.Instant;

public record CommonTicketBalanceResponse(Long userId, long balance, Instant updatedAt) {
}

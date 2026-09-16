package kr.co.cking.event.application.dto;

import java.util.UUID;

/** Controller가 {@code EntryRequest}를 변환해 전달하는 응모 커맨드. */
public record EntryCommand(Long userId, UUID requestId, Integer ticketCount) {
}

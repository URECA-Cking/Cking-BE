package kr.co.cking.event.application.dto;

import kr.co.cking.event.repository.EventEntryView;

import java.time.Instant;

public record EntryHistoryItemResponse(Long entryId, Long usedTicketCount, Instant appliedAt) {

    public static EntryHistoryItemResponse from(EventEntryView entry) {
        return new EntryHistoryItemResponse(entry.getEntryId(), entry.getUsedTicketCount(), entry.getAppliedAt());
    }
}

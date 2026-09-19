package kr.co.cking.event.repository;

import java.time.Instant;

public interface EventEntryView {
    Long getEntryId();
    Long getUsedTicketCount();
    Instant getAppliedAt();
}

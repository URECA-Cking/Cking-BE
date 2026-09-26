package kr.co.cking.event.repository;

import java.time.Instant;

public interface EventEntryView {
    Long getEntryId();
    Long getUsedTicketCount();
    Instant getAppliedAt();

    /** SPEND Ledger가 공용 Ledger에 있으면 true(COMMON), 아니면 false(CREATOR). */
    Boolean getCommon();
}

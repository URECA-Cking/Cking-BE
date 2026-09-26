package kr.co.cking.event.application.dto;

import kr.co.cking.event.repository.EventEntryView;
import kr.co.cking.ticket.domain.CouponType;

import java.time.Instant;

public record EntryHistoryItemResponse(Long entryId, Long usedTicketCount, CouponType couponType, Instant appliedAt) {

    public static EntryHistoryItemResponse from(EventEntryView entry) {
        CouponType couponType = Boolean.TRUE.equals(entry.getCommon()) ? CouponType.COMMON : CouponType.CREATOR;
        return new EntryHistoryItemResponse(entry.getEntryId(), entry.getUsedTicketCount(), couponType, entry.getAppliedAt());
    }
}

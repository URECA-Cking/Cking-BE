package kr.co.cking.event.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cking.event.repository.EventEntryView;
import kr.co.cking.ticket.domain.CouponType;

import java.time.Instant;

public record EntryHistoryItemResponse(
        Long entryId,
        Long usedTicketCount,
        @Schema(description = "응모에 쓴 응모권 종류", example = "COMMON") CouponType couponType,
        Instant appliedAt
) {

    public static EntryHistoryItemResponse from(EventEntryView entry) {
        CouponType couponType = Boolean.TRUE.equals(entry.getCommon()) ? CouponType.COMMON : CouponType.CREATOR;
        return new EntryHistoryItemResponse(entry.getEntryId(), entry.getUsedTicketCount(), couponType, entry.getAppliedAt());
    }
}

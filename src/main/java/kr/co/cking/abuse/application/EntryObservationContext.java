package kr.co.cking.abuse.application;

import java.time.Instant;
import java.util.UUID;
import kr.co.cking.ticket.domain.CouponType;

/** Event 조회 후 확정된 응모 시도와 요청 응모권 종류의 관찰용 scalar context다. */
public record EntryObservationContext(
        Long userId,
        Long creatorId,
        Long eventId,
        UUID requestId,
        CouponType couponType,
        Instant requestedAt
) {
}

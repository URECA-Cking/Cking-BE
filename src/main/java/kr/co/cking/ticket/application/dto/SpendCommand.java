package kr.co.cking.ticket.application.dto;

import java.util.Map;

import kr.co.cking.ticket.domain.CouponType;

/**
 * SPEND Stream({@code stream:ticket-deducted}) Consumer가 {@code TicketSpendLedgerService}를
 * 호출할 때 전달하는 커맨드. 필드는 {@code entry-spend.lua}가 XADD하는 필드와 동일하다.
 */
public record SpendCommand(
        Long eventId,
        Long userId,
        Long creatorId,
        String requestId,
        Long ticketCount,
        CouponType couponType
) {
    /**
     * {@code stream:ticket-deducted} 메시지 필드를 커맨드로 변환한다. barrier 메시지
     * ({@code type=EVENT_ENTRY_CLOSED})는 이 메서드로 넘기지 않고 Listener가 먼저 걸러낸다.
     * {@code couponType} 필드가 없는 배포 전 메시지·Dead Stream 원본은 CREATOR로 해석한다
     * (이슈 #243, D6).
     */
    public static SpendCommand fromStreamFields(Map<String, String> fields) {
        String couponType = fields.get("couponType");
        return new SpendCommand(
                Long.valueOf(fields.get("eventId")),
                Long.valueOf(fields.get("userId")),
                Long.valueOf(fields.get("creatorId")),
                fields.get("requestId"),
                Long.valueOf(fields.get("ticketCount")),
                couponType == null ? CouponType.CREATOR : CouponType.valueOf(couponType)
        );
    }
}

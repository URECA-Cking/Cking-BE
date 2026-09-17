package kr.co.cking.ticket.application.dto;

import java.util.Map;

/**
 * SPEND Stream({@code stream:ticket-deducted}) Consumer가 {@code TicketSpendLedgerService}를
 * 호출할 때 전달하는 커맨드. 필드는 {@code entry-spend.lua}가 XADD하는 필드와 동일하다.
 */
public record SpendCommand(
        Long eventId,
        Long userId,
        Long creatorId,
        String requestId,
        Long ticketCount
) {
    /**
     * {@code stream:ticket-deducted} 메시지 필드를 커맨드로 변환한다. barrier 메시지
     * ({@code type=EVENT_ENTRY_CLOSED})는 이 메서드로 넘기지 않고 Listener가 먼저 걸러낸다.
     */
    public static SpendCommand fromStreamFields(Map<String, String> fields) {
        return new SpendCommand(
                Long.valueOf(fields.get("eventId")),
                Long.valueOf(fields.get("userId")),
                Long.valueOf(fields.get("creatorId")),
                fields.get("requestId"),
                Long.valueOf(fields.get("ticketCount"))
        );
    }
}

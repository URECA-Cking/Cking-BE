package kr.co.cking.redraw.application;

/** RedrawRequest 생성에 필요한 HTTP 비종속 명령 값이다. */
public record RedrawRequestCreateCommand(
        Long userId,
        Long eventId,
        String reason,
        String idempotencyKey
) {
}

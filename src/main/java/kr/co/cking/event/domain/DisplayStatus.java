package kr.co.cking.event.domain;

import java.time.Instant;

/**
 * API 노출용 표시 상태. {@link EventStatus}와 현재 시각을 함께 봐야 결정된다(API 명세 §4.1).
 */
public enum DisplayStatus {
    UPCOMING,
    IN_PROGRESS,
    CLOSED;

    /**
     * SCHEDULED는 시각 무관 UPCOMING, CLOSING 이후 상태는 시각 무관 CLOSED —
     * OPEN만 endAt과 비교가 필요하다.
     */
    public static DisplayStatus of(EventStatus status, Instant endAt, Instant now) {
        return switch (status) {
            case SCHEDULED -> UPCOMING;
            case OPEN -> now.isBefore(endAt) ? IN_PROGRESS : CLOSED;
            case CLOSING, CLOSED, DRAW_COMPLETED, PUBLISHED -> CLOSED;
            default -> throw new IllegalStateException("표시 상태가 정의되지 않은 status: " + status);
        };
    }
}

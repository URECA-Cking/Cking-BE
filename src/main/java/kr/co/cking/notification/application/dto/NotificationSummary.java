package kr.co.cking.notification.application.dto;

import java.time.Instant;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.event.domain.Event;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;

public record NotificationSummary(
        Long notificationId,
        EventInfo event,
        DrawingInfo drawing,
        NotificationType type,
        String title,
        String body,
        Instant createdAt,
        Instant readAt
) {

    /**
     * Notification과 연관 Event·Drawing을 API 응답용 요약으로 변환한다. 제재 알림처럼 이벤트·추첨과 무관한 알림은
     * {@code event}·{@code drawing}이 null이다.
     */
    public static NotificationSummary of(Notification notification, Event event, Drawing drawing) {
        return new NotificationSummary(
                notification.getNotificationId(),
                event == null ? null : new EventInfo(event.getEventId(), event.getTitle()),
                drawing == null ? null : new DrawingInfo(drawing.getId(), drawing.getDrawNo(), drawing.getDrawType()),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getCreatedAt(),
                notification.getReadAt()
        );
    }

    public record EventInfo(Long eventId, String title) {
    }

    public record DrawingInfo(Long drawingId, int drawNo, DrawingType drawType) {
    }
}

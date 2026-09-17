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

    /** Notification과 연관 Event·Drawing을 API 응답용 요약으로 변환한다. */
    public static NotificationSummary of(Notification notification, Event event, Drawing drawing) {
        return new NotificationSummary(
                notification.getNotificationId(),
                new EventInfo(event.getEventId(), event.getTitle()),
                new DrawingInfo(drawing.getId(), drawing.getDrawNo(), drawing.getDrawType()),
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

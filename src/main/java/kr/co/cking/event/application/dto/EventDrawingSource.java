package kr.co.cking.event.application.dto;

import java.time.Instant;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;

/** Drawing 실행 전 검증에 필요한 Event의 읽기 전용 정보다. */
public record EventDrawingSource(
        Long eventId,
        EventStatus status,
        Instant deletedAt
) {

    public static EventDrawingSource from(Event event) {
        return new EventDrawingSource(event.getEventId(), event.getStatus(), event.getDeletedAt());
    }
}

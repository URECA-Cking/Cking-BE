package kr.co.cking.event.presentation.dto;

import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;

/** Creator Event 관리 API의 응답 데이터를 정의한다. */
public final class EventManagementResponse {

    private EventManagementResponse() {
    }

    /** Event 명령 결과의 식별자와 상태를 반환한다. */
    public record Result(Long eventId, EventStatus status) {
        /** Event 엔티티를 간결한 명령 결과로 변환한다. */
        public static Result from(Event event) {
            return new Result(event.getEventId(), event.getStatus());
        }
    }
}

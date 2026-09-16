package kr.co.cking.event.presentation.dto;

import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import org.springframework.data.domain.Page;
import java.util.List;

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

    /** Creator Event 목록의 한 항목을 반환한다. */
    public record Item(Long eventId, String title, EventStatus status) {
        /** Event 엔티티를 목록 항목으로 변환한다. */
        public static Item from(Event event) { return new Item(event.getEventId(), event.getTitle(), event.getStatus()); }
    }

    /** 공통 목록 페이지 메타데이터를 반환한다. */
    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages, boolean hasNext) {
        /** Spring Page를 API 페이지 응답으로 변환한다. */
        public static <T> PageResult<T> from(Page<?> source, List<T> items) {
            return new PageResult<>(items, source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages(), source.hasNext());
        }
    }
}

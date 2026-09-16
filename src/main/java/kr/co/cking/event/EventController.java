package kr.co.cking.event;

import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventQueryService eventQueryService;

    @GetMapping("/api/events")
    public ApiResponse<PageResponse<EventSummary>> getEvents(
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) DisplayStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<EventSummary> events = PageResponse.from(eventQueryService.getEvents(creatorId, status, page, size));
        return ApiResponse.success(events);
    }

    @GetMapping("/api/events/{eventId}")
    public ApiResponse<EventDetail> getEvent(@PathVariable Long eventId, @RequestParam Long userId) {
        return ApiResponse.success(eventQueryService.getEvent(eventId, userId));
    }
}

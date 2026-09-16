package kr.co.cking.event;

import kr.co.cking.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventQueryService eventQueryService;

    @GetMapping("/api/events")
    public ApiResponse<Page<EventSummary>> getEvents(
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) DisplayStatus status,
            Pageable pageable
    ) {
        return ApiResponse.success(eventQueryService.getEvents(creatorId, status, pageable));
    }

    @GetMapping("/api/events/{eventId}")
    public ApiResponse<EventDetail> getEvent(@PathVariable Long eventId, @RequestParam Long userId) {
        return ApiResponse.success(eventQueryService.getEvent(eventId, userId));
    }
}

package kr.co.cking.event.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.event.application.dto.EventDetail;
import kr.co.cking.event.application.dto.EventSummary;
import kr.co.cking.event.domain.DisplayStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Event", description = "사용자 이벤트 조회 API")
public class EventController {

    private final EventQueryService eventQueryService;

    @Operation(
            summary = "이벤트 목록 조회",
            description = "크리에이터와 화면 표시 상태로 이벤트를 조회합니다. "
                    + "page는 0부터 시작하며 size의 기본값은 20, 최댓값은 100입니다."
    )
    @GetMapping("/api/events")
    public ApiResponse<PageResponse<EventSummary>> getEvents(
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) DisplayStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        PageResponse<EventSummary> events = PageResponse.from(eventQueryService.getEvents(creatorId, status, page, size));
        return ApiResponse.success(events);
    }

    @Operation(
            summary = "이벤트 상세 조회",
            description = "이벤트 정보와 요청 사용자의 해당 크리에이터 보유 응모권 수를 함께 조회합니다."
    )
    @GetMapping("/api/events/{eventId}")
    public ApiResponse<EventDetail> getEvent(@PathVariable Long eventId, @RequestParam Long userId) {
        return ApiResponse.success(eventQueryService.getEvent(eventId, userId));
    }
}

package kr.co.cking.event.presentation;

import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.event.application.AdminClosingStatusQueryService;
import kr.co.cking.event.presentation.dto.ClosingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 마감 상태 조회 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Event Closing", description = "이벤트 수동 마감 및 마감 상태 조회 API")
public class AdminClosingStatusController {

    private final AdminClosingStatusQueryService adminClosingStatusQueryService;

    /** 관리자가 조회 권한을 가진 Event의 CLOSING 또는 CLOSED 상태만 반환한다. */
    @Operation(
            summary = "마감 상태 조회",
            description = "관리자가 이벤트의 마감 상태(CLOSING 또는 CLOSED)만 조회합니다. "
                    + "진행률, Pending 수, cutoff Stream ID 등 내부 Stream 정보는 반환하지 않습니다."
    )
    @GetMapping("/api/admin/events/{eventId}/closing-status")
    public ApiResponse<ClosingStatusResponse> getClosingStatus(
            @PathVariable @Positive Long eventId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(ClosingStatusResponse.from(
                adminClosingStatusQueryService.getClosingStatus(userId, eventId)));
    }
}

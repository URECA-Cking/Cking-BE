package kr.co.cking.event.presentation;

import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.event.application.AdminClosingStatusQueryService;
import kr.co.cking.event.presentation.dto.ClosingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 관리자 마감 상태 조회 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
public class AdminClosingStatusController {

    private final AdminClosingStatusQueryService adminClosingStatusQueryService;

    /** 관리자가 조회 권한을 가진 Event의 CLOSING 또는 CLOSED 상태만 반환한다. */
    @GetMapping("/api/admin/events/{eventId}/closing-status")
    public ApiResponse<ClosingStatusResponse> getClosingStatus(
            @PathVariable @Positive Long eventId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(ClosingStatusResponse.from(
                adminClosingStatusQueryService.getClosingStatus(userId, eventId)));
    }
}

package kr.co.cking.notification.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.notification.application.NotificationReadResult;
import kr.co.cking.notification.application.NotificationReadService;
import kr.co.cking.notification.application.NotificationQueryService;
import kr.co.cking.notification.application.dto.NotificationSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Notification 관련 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Notification", description = "인앱 알림 조회와 읽음 처리 API")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationReadService notificationReadService;

    /** 요청 사용자의 알림을 페이지 단위로 반환한다. */
    @GetMapping("/api/me/notifications")
    @Operation(summary = "내 알림 조회", description = "인증된 사용자의 알림을 페이지로 조회합니다.")
    public ApiResponse<PageResponse<NotificationSummary>> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(notificationQueryService.findMine(memberId, page, size)));
    }

    /** 요청 사용자의 Notification을 읽음 처리하고 최초 읽음 시각을 반환한다. */
    @PatchMapping("/api/me/notifications/{notificationId}/read")
    @Operation(summary = "알림 읽음 처리", description = "인증된 사용자가 소유한 알림을 읽음 처리합니다.")
    public ApiResponse<NotificationReadResult> read(
            @PathVariable @Positive Long notificationId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(notificationReadService.read(memberId, notificationId));
    }
}

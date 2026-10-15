package kr.co.cking.notification.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.notification.application.NotificationHideResult;
import kr.co.cking.notification.application.NotificationHideService;
import kr.co.cking.notification.application.NotificationReadAllResult;
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
    private final NotificationHideService notificationHideService;

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

    /** 요청 사용자의 Notification을 알림함에서 숨기고 최초 숨김 시각을 반환한다. */
    @PatchMapping("/api/me/notifications/{notificationId}/hide")
    @Operation(summary = "알림 숨김", description = "인증된 사용자가 소유한 알림을 알림함에서 숨깁니다. 알림은 삭제하지 않으며 숨긴 알림은 목록과 읽음 처리에서 제외됩니다. 이미 숨긴 알림을 다시 요청하면 최초 숨김 시각을 그대로 반환합니다.")
    public ApiResponse<NotificationHideResult> hide(
            @PathVariable @Positive Long notificationId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(notificationHideService.hide(memberId, notificationId));
    }

    /** 요청 사용자의 미확인 알림 전체를 읽음 처리하고 새로 처리한 건수를 반환한다. */
    @PatchMapping("/api/me/notifications/read")
    @Operation(summary = "내 알림 전체 읽음 처리", description = "인증된 사용자의 읽지 않은 알림을 페이지와 관계없이 모두 읽음 처리하고 새로 처리한 건수를 반환합니다. 반복 요청 시 이미 읽은 알림의 시각은 유지됩니다.")
    public ApiResponse<NotificationReadAllResult> readAll(@CurrentMemberId Long memberId) {
        return ApiResponse.success(notificationReadService.readAll(memberId));
    }
}

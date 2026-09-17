package kr.co.cking.notification.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.notification.application.NotificationReadResult;
import kr.co.cking.notification.application.NotificationReadService;
import kr.co.cking.notification.application.NotificationQueryService;
import kr.co.cking.notification.application.dto.NotificationSummary;
import kr.co.cking.notification.presentation.dto.NotificationReadRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Notification 관련 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationReadService notificationReadService;

    /** 요청 사용자의 알림을 페이지 단위로 반환한다. */
    @GetMapping("/api/me/notifications")
    public ApiResponse<PageResponse<NotificationSummary>> findMine(
            @RequestParam @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(notificationQueryService.findMine(userId, page, size)));
    }

    /** 요청 사용자의 Notification을 읽음 처리하고 최초 읽음 시각을 반환한다. */
    @PatchMapping("/api/me/notifications/{notificationId}/read")
    public ApiResponse<NotificationReadResult> read(
            @PathVariable @Positive Long notificationId,
            @Valid @RequestBody NotificationReadRequest request
    ) {
        return ApiResponse.success(notificationReadService.read(request.userId(), notificationId));
    }
}

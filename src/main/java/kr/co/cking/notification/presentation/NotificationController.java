package kr.co.cking.notification.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.notification.application.NotificationQueryService;
import kr.co.cking.notification.application.dto.NotificationSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    @GetMapping("/api/me/notifications")
    public ApiResponse<PageResponse<NotificationSummary>> findMine(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(notificationQueryService.findMine(userId, page, size)));
    }
}

package kr.co.cking.notification.presentation;

import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.notification.application.NotificationReadResult;
import kr.co.cking.notification.application.NotificationReadService;
import kr.co.cking.notification.presentation.dto.NotificationReadRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Notification 관련 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationReadService notificationReadService;

    /** 요청 사용자의 Notification을 읽음 처리하고 최초 읽음 시각을 반환한다. */
    @PatchMapping("/api/me/notifications/{notificationId}/read")
    public ApiResponse<NotificationReadResult> read(
            @PathVariable Long notificationId,
            @Valid @RequestBody NotificationReadRequest request
    ) {
        return ApiResponse.success(notificationReadService.read(request.userId(), notificationId));
    }
}

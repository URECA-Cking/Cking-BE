package kr.co.cking.notification.presentation.dto;

import jakarta.validation.constraints.NotNull;

/** Notification 읽음 처리 요청 본문을 표현한다. */
public record NotificationReadRequest(
        @NotNull(message = "userId는 필수입니다.") Long userId
) {
}

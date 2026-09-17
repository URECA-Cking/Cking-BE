package kr.co.cking.notification.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Notification 읽음 처리 요청 본문을 표현한다. */
public record NotificationReadRequest(
        @NotNull(message = "userId는 필수입니다.") @Positive(message = "userId는 양수여야 합니다.") Long userId
) {
}

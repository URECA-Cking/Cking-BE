package kr.co.cking.notification.application;

import java.time.Instant;

/** Notification 숨김 처리 결과를 전달한다. */
public record NotificationHideResult(Long notificationId, Instant hiddenAt) {
}

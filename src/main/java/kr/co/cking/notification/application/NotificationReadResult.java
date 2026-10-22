package kr.co.cking.notification.application;

import java.time.Instant;

/** Notification 읽음 처리 결과를 전달한다. */
public record NotificationReadResult(Long notificationId, Instant readAt) {
}

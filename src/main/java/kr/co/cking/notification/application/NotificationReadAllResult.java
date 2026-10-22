package kr.co.cking.notification.application;

/** 전체 알림 읽음 요청에서 새로 읽음 처리한 건수를 전달한다. */
public record NotificationReadAllResult(int readCount) {
}

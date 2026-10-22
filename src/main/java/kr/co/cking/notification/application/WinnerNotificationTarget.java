package kr.co.cking.notification.application;

/** 결과 공개 알림을 받을 Winner의 식별자 묶음이다. */
public record WinnerNotificationTarget(Long winnerId, Long memberId) {
}

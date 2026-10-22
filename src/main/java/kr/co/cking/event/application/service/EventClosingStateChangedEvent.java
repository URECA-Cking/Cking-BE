package kr.co.cking.event.application.service;

/** 마감 상태 전이 커밋 후 Event 조회 캐시를 무효화하기 위한 내부 이벤트. */
public record EventClosingStateChangedEvent(Long eventId) {
}

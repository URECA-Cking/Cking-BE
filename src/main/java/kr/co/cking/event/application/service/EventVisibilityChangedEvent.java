package kr.co.cking.event.application.service;

/** Event 보관·복원 뒤 사용자 조회 상세 캐시만 비우기 위한 도메인 이벤트다. */
public record EventVisibilityChangedEvent(Long eventId) {
}

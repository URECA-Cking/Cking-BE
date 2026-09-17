package kr.co.cking.event.application.service;

/** OPEN 전이가 커밋된 뒤 처리할 후속 작업을 알린다. */
public record EventOpenedEvent(Long eventId) {
}

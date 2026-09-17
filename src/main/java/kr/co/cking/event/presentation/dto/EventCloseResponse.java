package kr.co.cking.event.presentation.dto;

import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.EventStatus;

/** 비동기 마감 시작 요청의 응답이다. */
public record EventCloseResponse(Long eventId, EventStatus status) {

    public static EventCloseResponse from(EventClosingService.ClosingResult result) {
        return new EventCloseResponse(result.eventId(), result.status());
    }
}

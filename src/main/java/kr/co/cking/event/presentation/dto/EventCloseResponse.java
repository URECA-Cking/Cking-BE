package kr.co.cking.event.presentation.dto;

import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.EventStatus;

/** 수동 마감 요청의 실제 현재 상태 응답이다. */
public record EventCloseResponse(Long eventId, EventStatus status) {

    public static EventCloseResponse from(EventClosingService.ClosingResult result) {
        return new EventCloseResponse(result.eventId(), result.status());
    }
}

package kr.co.cking.event.application.service;

import kr.co.cking.event.domain.EventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 자동·수동 마감 요청이 함께 사용하는 시스템2의 마감 시작 진입점. */
@Service
@RequiredArgsConstructor
public class EventClosingService {

    private final EventCutoffBarrier eventCutoffBarrier;
    private final EventCommandService eventCommandService;

    /** Gate를 닫고 cutoff를 확정한 뒤 OPEN→CLOSING 전이를 요청한다. */
    public ClosingResult startClosing(Long eventId) {
        String cutoffStreamId = eventCutoffBarrier.close(eventId);
        EventStatus status = eventCommandService.startClosing(eventId, cutoffStreamId);
        return new ClosingResult(eventId, status);
    }

    public record ClosingResult(Long eventId, EventStatus status) {
    }
}

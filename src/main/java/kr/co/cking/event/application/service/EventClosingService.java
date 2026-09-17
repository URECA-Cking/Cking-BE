package kr.co.cking.event.application.service;

import kr.co.cking.event.domain.EventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 시스템2가 소유하는 수동·자동 마감 시작 진입점이다. */
@Service
@RequiredArgsConstructor
public class EventClosingService {

    private final EventCutoffBarrier eventCutoffBarrier;
    private final EventCommandService eventCommandService;

    /** Gate를 닫고 cutoff를 확정한 뒤 마감을 시작하거나, 이미 진행·완료된 현재 상태를 반환한다. */
    public ClosingResult startClosing(Long eventId) {
        String cutoffStreamId = eventCutoffBarrier.close(eventId);
        EventStatus status = eventCommandService.startClosing(eventId, cutoffStreamId);
        return new ClosingResult(eventId, status);
    }

    /** 마감 시작 요청의 외부 전달용 결과다. */
    public record ClosingResult(Long eventId, EventStatus status) {
    }
}

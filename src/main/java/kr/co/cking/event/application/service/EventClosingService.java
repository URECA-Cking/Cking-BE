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

    /** Gate를 닫고 cutoff를 확정한 뒤 Event를 CLOSING으로 전이한다. */
    public ClosingResult startClosing(Long eventId) {
        String cutoffStreamId = eventCutoffBarrier.close(eventId);
        eventCommandService.startClosing(eventId, cutoffStreamId);
        return new ClosingResult(eventId, EventStatus.CLOSING);
    }

    /** 마감 시작 요청의 외부 전달용 결과다. */
    public record ClosingResult(Long eventId, EventStatus status) {
    }
}

package kr.co.cking.event.application.service;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 자동·수동 마감 요청이 함께 사용하는 시스템2의 마감 시작 진입점. */
@Service
@RequiredArgsConstructor
public class EventClosingService {

    private final EventCutoffBarrier eventCutoffBarrier;
    private final EventCommandService eventCommandService;
    private final EventRepository eventRepository;

    /** Gate를 닫고 cutoff를 확정한 뒤 OPEN→CLOSING 전이를 요청한다. */
    public ClosingResult startClosing(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (event.getStatus() == EventStatus.CLOSING || event.getStatus() == EventStatus.CLOSED) {
            return new ClosingResult(eventId, event.getStatus());
        }
        if (event.getStatus() != EventStatus.OPEN) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }

        String cutoffStreamId = eventCutoffBarrier.close(eventId);
        EventStatus status = eventCommandService.startClosing(eventId, cutoffStreamId);
        return new ClosingResult(eventId, status);
    }

    public record ClosingResult(Long eventId, EventStatus status) {
    }
}

package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Drawing 도메인이 Event Entity에 직접 의존하지 않도록 실행 전 상태를 제공한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventDrawingQueryService {

    private final EventRepository eventRepository;

    public EventDrawingSource getDrawingSource(Long eventId) {
        return eventRepository.findById(eventId)
                .map(EventDrawingSource::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

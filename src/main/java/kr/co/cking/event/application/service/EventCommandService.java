package kr.co.cking.event.application.service;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class EventCommandService {

    private final EventRepository eventRepository;

    public void approve(Long eventId) {
        // Event 상태 변경은 이 서비스 진입점으로만 수행한다.
        findEvent(eventId).approve();
    }

    public void reject(Long eventId, String reason) {
        findEvent(eventId).reject();
    }

    public void changeToDraft(Long eventId) {
        findEvent(eventId).changeToDraft();
    }

    private Event findEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

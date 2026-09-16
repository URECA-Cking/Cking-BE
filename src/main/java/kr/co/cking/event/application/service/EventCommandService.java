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

    /** DRAFT Event를 승인 대기 상태로 전이한다. */
    public void requestApproval(Long eventId) {
        findEvent(eventId).requestApproval();
    }

    /** 승인 대기 Event를 예약 상태로 전이한다. */
    public void approve(Long eventId) {
        // Event 상태 변경은 이 서비스 진입점으로만 수행한다.
        findEvent(eventId).approve();
    }

    /** 승인 대기 Event를 거절 상태로 전이한다. */
    public void reject(Long eventId, String reason) {
        findEvent(eventId).reject();
    }

    /** 거절된 Event를 다시 수정 가능한 초안 상태로 전이한다. */
    public void changeToDraft(Long eventId) {
        findEvent(eventId).changeToDraft();
    }

    /** 식별자로 Event를 조회하고 없으면 공통 조회 오류를 발생시킨다. */
    private Event findEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

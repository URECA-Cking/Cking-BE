package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인의 조회 유스케이스가 Event 존재 여부만 검증할 수 있게 한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventExistenceQueryService {

    private final EventRepository eventRepository;

    /** 요청한 Event가 존재하는지 검증하고, 없으면 공통 리소스 없음 오류를 던진다. */
    public void validateExists(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}

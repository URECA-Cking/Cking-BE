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

    /**
     * 명령 경로(공개 등)에서 상태를 확인·변경할 때 쓴다. Event 행을 잠가 조회하므로, 같은
     * Tx 안에서 이 값을 근거로 나중에 쓰기를 해도 REPEATABLE READ 스냅샷 때문에 다른 트랜잭션이
     * 커밋한 최신 상태를 놓치지 않는다(일반 조회는 트랜잭션 시작 시점의 스냅샷을 반환할 수 있다).
     */
    @Transactional
    public EventDrawingSource getDrawingSourceForUpdate(Long eventId) {
        return eventRepository.findByEventId(eventId)
                .map(EventDrawingSource::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

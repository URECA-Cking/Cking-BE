package kr.co.cking.event.application;

import kr.co.cking.event.application.service.EventGateLoader;
import kr.co.cking.event.application.service.EventOpenedEvent;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.event.application.service.EventClosingStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Event 상태 전이가 DB에 반영된 뒤 조회 캐시를 제거하고, OPEN 전이 시 응모 Gate를 적재한다. */
@Component
@Slf4j
@RequiredArgsConstructor
class EventCacheInvalidationListener {

    private final EventQueryService eventQueryService;
    private final EventGateLoader eventGateLoader;
    private final EventRepository eventRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateAfterOpen(EventOpenedEvent event) {
        invalidate(event.eventId(), "opening");
        loadGate(event.eventId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateAfterClosingTransition(EventClosingStateChangedEvent event) {
        invalidate(event.eventId(), "closing transition");
        closeGate(event.eventId());
    }

    // 복원 틱이 stale한 OPEN 조회로 Gate를 다시 열었어도, 마감 전이 커밋 직후 닫는다. 실패하면 스케줄러가 CLOSING을 재조회해 닫는다.
    private void closeGate(Long eventId) {
        try {
            eventGateLoader.close(eventId);
        } catch (RuntimeException exception) {
            log.error("Failed to close entry gate after closing transition, scheduler will retry: eventId={}", eventId, exception);
        }
    }

    // 이미 커밋된 뒤라 롤백할 수 없다. 실패하면 EventLifecycleScheduler가 다음 틱에 다시 적재한다.
    private void loadGate(Long eventId) {
        try {
            eventRepository.findById(eventId).ifPresent(eventGateLoader::load);
        } catch (RuntimeException exception) {
            log.error("Failed to load entry gate after opening, scheduler will retry: eventId={}", eventId, exception);
        }
    }

    private void invalidate(Long eventId, String transition) {
        try {
            eventQueryService.invalidate(eventId);
        } catch (RuntimeException exception) {
            log.warn("Failed to evict event cache after {}: eventId={}", transition, eventId, exception);
        }
    }
}

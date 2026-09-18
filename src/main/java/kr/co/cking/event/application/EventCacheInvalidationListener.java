package kr.co.cking.event.application;

import kr.co.cking.event.application.service.EventOpenedEvent;
import kr.co.cking.event.application.service.EventClosingStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Event 상태 전이가 DB에 반영된 뒤 조회 캐시를 제거한다. */
@Component
@Slf4j
@RequiredArgsConstructor
class EventCacheInvalidationListener {

    private final EventQueryService eventQueryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateAfterOpen(EventOpenedEvent event) {
        invalidate(event.eventId(), "opening");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateAfterClosingTransition(EventClosingStateChangedEvent event) {
        invalidate(event.eventId(), "closing transition");
    }

    private void invalidate(Long eventId, String transition) {
        try {
            eventQueryService.invalidate(eventId);
        } catch (RuntimeException exception) {
            log.warn("Failed to evict event cache after {}: eventId={}", transition, eventId, exception);
        }
    }
}

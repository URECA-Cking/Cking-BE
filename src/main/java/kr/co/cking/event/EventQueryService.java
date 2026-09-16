package kr.co.cking.event;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.ticket.TicketBalanceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQueryService {

    private final EventRepository eventRepository;
    private final Clock clock;
    private final TicketBalanceQueryService ticketBalanceQueryService;
    private final EventCache eventCache;

    public Page<EventSummary> getEvents(Long creatorId, DisplayStatus displayStatus, Pageable pageable) {
        Instant now = clock.instant();
        return eventRepository.search(creatorId, displayStatus, now, pageable)
                .map(event -> EventSummary.from(event, now));
    }

    public EventDetail getEvent(Long eventId, Long userId) {
        CachedEvent event = getCachedEvent(eventId);
        long myTicketBalance = ticketBalanceQueryService.getBalance(event.creatorId(), userId);
        return EventDetail.of(event, clock.instant(), myTicketBalance);
    }

    /**
     * 다른 도메인(entry 등)이 이벤트 조회 시 Repository를 직접 참조하지 않도록 하는 진입점.
     */
    public CachedEvent getCachedEvent(Long eventId) {
        return eventCache.find(eventId).orElseGet(() -> loadAndCache(eventId));
    }

    private CachedEvent loadAndCache(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
        CachedEvent cached = CachedEvent.from(event);
        eventCache.save(cached);
        return cached;
    }
}

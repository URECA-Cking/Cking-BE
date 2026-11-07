package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 이벤트 상세 캐시를 DB에서 채운다. 이벤트와 소유 회원을 한 트랜잭션에서 읽어 트랜잭션 설정·정리 왕복을 한 번만 쓴다. */
@Component
@RequiredArgsConstructor
public class EventCacheLoader {

    private final EventRepository eventRepository;
    private final CreatorQueryService creatorQueryService;
    private final EventCache eventCache;

    @Transactional(readOnly = true)
    public CachedEvent load(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .filter(e -> e.getDeletedAt() == null && !e.isArchived() && e.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
        Long ownerMemberId = creatorQueryService.findOwnerMemberId(event.getCreatorId())
                .orElseThrow(() -> new IllegalStateException("Event Creator의 소유 회원을 찾을 수 없습니다."));
        CachedEvent cached = CachedEvent.from(event, ownerMemberId);
        eventCache.save(cached);
        return cached;
    }
}

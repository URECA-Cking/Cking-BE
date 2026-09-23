package kr.co.cking.event.application;

import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.EntryStatusResponse;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventEntryAggregate;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * 실시간 응모 현황 조회(FR-P2-045~051). Event가 {@code OPEN}/{@code CLOSING}이고 집계 키가
 * 적재돼 있으면 Redis에서, 그 외에는 DB {@code event_entry} 집계로 대체한다("키 없음 = 0
 * 금지" 원칙 - 집계 키 미적재를 0건으로 보지 않는다). Snapshot·추첨 판단에는 쓰이지 않는
 * 표시 전용 조회다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntryStatusQueryService {

    private final EventQueryService eventQueryService;
    private final EventEntryRepository eventEntryRepository;
    private final MemberRepository memberRepository;
    private final StringRedisTemplate redisTemplate;

    public EntryStatusResponse getStatus(Long eventId, Long userId) {
        CachedEvent event = eventQueryService.getCachedEvent(eventId);
        if (userId != null && !memberRepository.existsById(userId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }

        if (allowsRealtime(event.status())) {
            Optional<EntryStatusResponse> realtime = readFromRedis(eventId, userId);
            if (realtime.isPresent()) {
                return realtime.get();
            }
        }
        return readFromDb(eventId, userId);
    }

    private boolean allowsRealtime(EventStatus status) {
        return status == EventStatus.OPEN || status == EventStatus.CLOSING;
    }

    private Optional<EntryStatusResponse> readFromRedis(Long eventId, Long userId) {
        String totalValue = redisTemplate.opsForValue().get(EntryRedisKeys.entryTotal(eventId));
        if (totalValue == null) {
            return Optional.empty();
        }

        HashOperations<String, String, String> entrants = redisTemplate.opsForHash();
        String entrantsKey = EntryRedisKeys.entrants(eventId);
        long participantCount = entrants.size(entrantsKey);
        Long myTicketCount = null;
        if (userId != null) {
            String myValue = entrants.get(entrantsKey, String.valueOf(userId));
            myTicketCount = myValue == null ? 0L : Long.parseLong(myValue);
        }

        return Optional.of(new EntryStatusResponse(
                eventId, participantCount, Long.parseLong(totalValue), myTicketCount, true));
    }

    private EntryStatusResponse readFromDb(Long eventId, Long userId) {
        List<EventEntryAggregate> aggregates = eventEntryRepository.aggregateByEvent(eventId);
        long totalTicketCount = aggregates.stream().mapToLong(EventEntryAggregate::getTicketCount).sum();
        Long myTicketCount = null;
        if (userId != null) {
            myTicketCount = aggregates.stream()
                    .filter(aggregate -> aggregate.getMemberId().equals(userId))
                    .mapToLong(EventEntryAggregate::getTicketCount)
                    .findFirst()
                    .orElse(0L);
        }

        return new EntryStatusResponse(eventId, aggregates.size(), totalTicketCount, myTicketCount, false);
    }
}

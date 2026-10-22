package kr.co.cking.event.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>DB 폴백 경로는 매 호출마다 {@code event_entry}를 group by로 재집계하므로, CLOSED 이후
 * 계속 조회되는 인기 이벤트의 DB 부하를 줄이기 위해 이벤트별로 5초짜리 인스턴스 로컬 캐시를
 * 둔다(표시용 값이라 인스턴스 간 공유·무효화 없이 TTL만으로 충분하다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntryStatusQueryService {

    private static final Duration DB_AGGREGATE_CACHE_TTL = Duration.ofSeconds(5);

    private final EventQueryService eventQueryService;
    private final EventEntryRepository eventEntryRepository;
    private final MemberRepository memberRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    private final ConcurrentHashMap<Long, CachedAggregate> dbAggregateCache = new ConcurrentHashMap<>();

    private record CachedAggregate(List<EventEntryAggregate> aggregates, Instant expiresAt) {
    }

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
        // entrants(Hash)는 빈 채로 존재할 수 없어서, 참여자가 아직 없는 정상 상태("0")에서는
        // entrantsKey가 원래 없는 게 맞다. 그 외에 entry-total은 있는데 entrantsKey가 없으면
        // entrants만 eviction 등으로 유실된 부분 desync다 - HLEN·HGET이 조용히 0/null을 반환해
        // 실제로는 참여자가 있는데도 0으로 잘못 응답하게 되므로, 이때는 Redis를 신뢰하지 않고
        // DB 집계로 완전히 대체한다.
        if (!"0".equals(totalValue) && Boolean.FALSE.equals(redisTemplate.hasKey(entrantsKey))) {
            return Optional.empty();
        }
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
        List<EventEntryAggregate> aggregates = loadAggregatesCached(eventId);
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

    // ponytail: 만료된 엔트리를 제거하지 않는 무제한 캐시 - eventId당 1건이라 이 프로젝트
    // 규모에서는 무시 가능하지만, 이벤트 수가 매우 많아지면 Caffeine 등 크기 제한 캐시로 교체.
    private List<EventEntryAggregate> loadAggregatesCached(Long eventId) {
        Instant now = clock.instant();
        CachedAggregate cached = dbAggregateCache.get(eventId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.aggregates();
        }
        List<EventEntryAggregate> aggregates = eventEntryRepository.aggregateByEvent(eventId);
        dbAggregateCache.put(eventId, new CachedAggregate(aggregates, now.plus(DB_AGGREGATE_CACHE_TTL)));
        return aggregates;
    }
}

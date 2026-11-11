package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.CachedEventPage;
import kr.co.cking.event.application.dto.EventCounts;
import kr.co.cking.event.application.dto.EventDetail;
import kr.co.cking.event.application.dto.EventSummary;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.CommonTicketBalanceQueryService;
import kr.co.cking.ticket.application.TicketBalanceQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class EventQueryService {

    private static final Sort EVENT_LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt", "eventId");

    private final EventRepository eventRepository;
    private final Clock clock;
    private final TicketBalanceQueryService ticketBalanceQueryService;
    private final CommonTicketBalanceQueryService commonTicketBalanceQueryService;
    private final EventCache eventCache;
    private final EventListCache eventListCache;
    private final EventCountsCache eventCountsCache;
    private final MemberRepository memberRepository;
    private final EventCacheLoader eventCacheLoader;
    private final EventCacheMetrics eventCacheMetrics;
    private final EventCacheRefresher eventCacheRefresher;
    private final TransactionOperations readOnlyTransaction;
    private final ConcurrentMap<Long, CompletableFuture<CachedEvent>> cachedEventLoads = new ConcurrentHashMap<>();

    @Autowired
    public EventQueryService(EventRepository eventRepository, Clock clock,
                             TicketBalanceQueryService ticketBalanceQueryService,
                             CommonTicketBalanceQueryService commonTicketBalanceQueryService,
                             EventCache eventCache, EventListCache eventListCache, EventCountsCache eventCountsCache,
                             MemberRepository memberRepository, EventCacheLoader eventCacheLoader,
                             EventCacheMetrics eventCacheMetrics, EventCacheRefresher eventCacheRefresher,
                             PlatformTransactionManager transactionManager) {
        this(eventRepository, clock, ticketBalanceQueryService, commonTicketBalanceQueryService, eventCache,
                eventListCache, eventCountsCache, memberRepository, eventCacheLoader, eventCacheMetrics,
                eventCacheRefresher, readOnly(transactionManager));
    }

    EventQueryService(EventRepository eventRepository, Clock clock,
                      TicketBalanceQueryService ticketBalanceQueryService,
                      CommonTicketBalanceQueryService commonTicketBalanceQueryService,
                      EventCache eventCache, EventListCache eventListCache, EventCountsCache eventCountsCache,
                      MemberRepository memberRepository, EventCacheLoader eventCacheLoader,
                      EventCacheMetrics eventCacheMetrics, EventCacheRefresher eventCacheRefresher,
                      TransactionOperations readOnlyTransaction) {
        this.eventRepository = eventRepository;
        this.clock = clock;
        this.ticketBalanceQueryService = ticketBalanceQueryService;
        this.commonTicketBalanceQueryService = commonTicketBalanceQueryService;
        this.eventCache = eventCache;
        this.eventListCache = eventListCache;
        this.eventCountsCache = eventCountsCache;
        this.memberRepository = memberRepository;
        this.eventCacheLoader = eventCacheLoader;
        this.eventCacheMetrics = eventCacheMetrics;
        this.eventCacheRefresher = eventCacheRefresher;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    /**
     * API 명세 §1.7: page=0부터, size 기본 20·최대 100(범위 검증은 컨트롤러에서),
     * 정렬은 createdAt DESC에 eventId DESC를 tie-breaker로 고정한다 — 클라이언트가
     * 정렬을 고를 수 없다.
     *
     * <p>FR-P2-003: creatorId·displayStatus·page·size 조합을 키로 캐싱한다(TTL 5초,
     * endAt 초과 금지 — {@link EventListCache}). displayStatus는 원본 Event가 아니라
     * 읽는 시점의 now로 매번 다시 계산해서, 캐시 적중 시에도 굳지 않게 한다.
     *
     * <p>userId를 주면 각 항목에 공통 응모권 잔액(myCommonTicketBalance)을 캐시 밖에서 붙인다.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Page<EventSummary> getEvents(Long creatorId, DisplayStatus displayStatus, Long userId, int page, int size) {
        requireMember(userId);
        Instant now = clock.instant();
        Optional<CachedEventPage> cached = eventListCache.find(creatorId, displayStatus, page, size);
        if (cached.isPresent() && userId == null) {
            return toSummaryPage(cached.get(), page, size, now);
        }
        ListRead read = readOnlyTransaction.execute(status -> {
            CachedEventPage events = cached.orElseGet(() -> loadList(creatorId, displayStatus, page, size, now));
            Long commonBalance = userId == null ? null : commonTicketBalanceQueryService.getBalanceDetail(userId).balance();
            return new ListRead(events, commonBalance);
        });
        if (cached.isEmpty()) {
            eventListCache.save(creatorId, displayStatus, page, size, read.events());
        }
        Page<EventSummary> events = toSummaryPage(read.events(), page, size, now);
        if (read.commonBalance() == null) {
            return events;
        }
        long commonBalance = read.commonBalance();
        return events.map(e -> e.withMyCommonTicketBalance(commonBalance));
    }

    private CachedEventPage loadList(Long creatorId, DisplayStatus displayStatus, int page, int size, Instant now) {
        Pageable pageable = PageRequest.of(page, size, EVENT_LIST_SORT);
        Page<Event> result = eventRepository.search(creatorId, displayStatus, now, pageable);
        return new CachedEventPage(
                result.getContent().stream().map(CachedEvent::from).toList(),
                result.getTotalElements());
    }

    private void requireMember(Long userId) {
        if (userId != null && !memberRepository.existsById(userId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
    }

    private record ListRead(CachedEventPage events, Long commonBalance) {
    }

    private Page<EventSummary> toSummaryPage(CachedEventPage cached, int page, int size, Instant now) {
        List<EventSummary> summaries = cached.events().stream()
                .map(event -> EventSummary.from(event, now))
                .toList();
        return new PageImpl<>(summaries, PageRequest.of(page, size, EVENT_LIST_SORT), cached.totalElements());
    }

    /** 공개 이벤트 수를 표시 상태별로 한 번에 센다. total은 세 값의 합이고, 목록과 같은 5초 캐시 정책을 따른다. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public EventCounts getEventCounts() {
        Optional<EventCounts> cached = eventCountsCache.find();
        if (cached.isPresent()) {
            return cached.get();
        }
        Instant now = clock.instant();
        CountsRead read = readOnlyTransaction.execute(status -> {
            var counts = eventRepository.countByDisplayStatus(now);
            EventCounts result = new EventCounts(counts.getUpcoming() + counts.getInProgress() + counts.getClosed(),
                    counts.getUpcoming(), counts.getInProgress(), counts.getClosed());
            return new CountsRead(result, eventRepository.findEarliestOpenEndAt(now).orElse(null));
        });
        eventCountsCache.save(read.counts(), read.earliestOpenEndAt());
        return read.counts();
    }

    private record CountsRead(EventCounts counts, Instant earliestOpenEndAt) {
    }

    /** 크리에이터별 진행 중 이벤트 수를 한 번에 센다. 진행 중이 없는 크리에이터는 키가 없다. */
    public Map<Long, Long> countInProgressByCreatorIds(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Map.of();
        }
        return eventRepository.countInProgressByCreatorIds(creatorIds, clock.instant()).stream()
                .collect(Collectors.toMap(EventRepository.CreatorEventCount::getCreatorId,
                        EventRepository.CreatorEventCount::getTotal));
    }

    /** 공개 Event를 조회하고, 로그인한 호출자에게만 개인 응모권 잔액을 붙인다. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public EventDetail getEvent(Long eventId, Long userId) {
        requireMember(userId);
        CachedEvent event = getCachedEvent(eventId);
        if (userId == null) {
            return EventDetail.of(event, clock.instant(), 0L, 0L);
        }
        Balances balances = readOnlyTransaction.execute(status -> new Balances(
                ticketBalanceQueryService.getBalance(event.creatorId(), userId),
                commonTicketBalanceQueryService.getBalanceDetail(userId).balance()));
        return EventDetail.of(event, clock.instant(), balances.ticket(), balances.common());
    }

    private record Balances(long ticket, long common) {
    }

    /**
     * 공개 조회 진입점(entry 등 다른 도메인이 이벤트 조회 시 Repository를 직접 참조하지
     * 않도록 함). DRAFT/PENDING_APPROVAL/REJECTED는 대상에서 제외한다 — 그 상태의
     * Event가 필요하면 {@link #getEventForOperation}을 쓴다.
     * 트랜잭션 밖에서 부르면 적중은 DB 연결 없이 끝나도록 트랜잭션을 새로 열지 않는다.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public CachedEvent getCachedEvent(Long eventId) {
        Optional<CachedEvent> cached = eventCache.find(eventId);
        if (cached.isPresent()) {
            eventCacheMetrics.hit();
            refreshIfDue(cached.get());
            return cached.get();
        }
        eventCacheMetrics.miss();
        return load(eventId);
    }

    /**
     * 트랜잭션 밖의 호출은 캐시가 빈 순간 이벤트마다 한 요청만 읽고 나머지는 연결 없이 그 결과를 기다린다.
     * 트랜잭션 안의 호출은 이미 연결을 쥐고 있어, 기다리게 하면 읽는 요청이 연결을 받지 못해 함께 멈출 수 있다.
     * 그래서 자기 트랜잭션에서 바로 읽는다.
     */
    private CachedEvent load(Long eventId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            CachedEvent loaded = eventCacheMetrics.inTransactionLoad(() -> eventCacheLoader.load(eventId));
            eventCacheRefresher.loaded(eventId);
            return loaded;
        }
        return loadOnce(eventId);
    }

    private CachedEvent loadOnce(Long eventId) {
        CompletableFuture<CachedEvent> load = new CompletableFuture<>();
        CompletableFuture<CachedEvent> running = cachedEventLoads.putIfAbsent(eventId, load);
        if (running != null) {
            return eventCacheMetrics.loadWait(() -> awaitLoad(running));
        }
        try {
            CachedEvent loaded = eventCacheMetrics.singleFlightLoad(() -> eventCacheLoader.load(eventId));
            eventCacheRefresher.loaded(eventId);
            load.complete(loaded);
            return loaded;
        } catch (RuntimeException | Error exception) {
            load.completeExceptionally(exception);
            throw exception;
        } finally {
            cachedEventLoads.remove(eventId, load);
        }
    }

    private void refreshIfDue(CachedEvent cached) {
        Long eventId = cached.eventId();
        if (!clock.instant().isBefore(cached.endAt()) || !eventCacheRefresher.claim(eventId)) {
            return;
        }
        eventCacheRefresher.submit(() -> refresh(eventId));
    }

    private void refresh(Long eventId) {
        CompletableFuture<CachedEvent> load = new CompletableFuture<>();
        if (cachedEventLoads.putIfAbsent(eventId, load) != null) {
            return;
        }
        try {
            load.complete(eventCacheMetrics.refreshLoad(() -> eventCacheLoader.load(eventId)));
            eventCacheRefresher.loaded(eventId);
        } catch (RuntimeException exception) {
            eventCacheRefresher.failed();
            if (exception instanceof BusinessException business
                    && business.getErrorCode() == EventErrorCode.EVENT_NOT_FOUND) {
                eventCache.evict(eventId);
            }
            load.completeExceptionally(exception);
        } catch (Error error) {
            eventCacheRefresher.failed();
            load.completeExceptionally(error);
            throw error;
        } finally {
            cachedEventLoads.remove(eventId, load);
        }
    }

    private static CachedEvent awaitLoad(CompletableFuture<CachedEvent> running) {
        try {
            return running.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw exception;
        }
    }

    /**
     * 명령 처리(승인·거절·수정·삭제 등)용 조회 진입점. 공개 조회와 달리 모든 상태를
     * 대상으로 하고 캐시를 거치지 않는다 — 상태를 바꾸는 쪽이 stale 캐시를 근거로
     * 판단하면 안 되기 때문이다. 이 메서드로 조회한 뒤 상태를 바꿨다면 반드시
     * {@link #invalidate}를 호출한다.
     */
    public Event getEventForOperation(Long eventId) {
        return eventRepository.findById(eventId)
                .filter(e -> e.getDeletedAt() == null && !e.isArchived())
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /** Event 상태 변경 후(EventCommandService 등) 호출해서 cache:event를 무효화한다. */
    public void invalidate(Long eventId) {
        eventCache.evict(eventId);
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }
}

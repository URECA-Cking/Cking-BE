package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.CommonTicketBalanceQueryService;
import kr.co.cking.ticket.application.TicketBalanceQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EventCacheRefreshTest {

    private static final Duration AFTER = Duration.ofMillis(500);

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final EventCache eventCache = mock(EventCache.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong();
    private final List<Runnable> submitted = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    private final Event event = Event.builder()
            .creatorId(7L)
            .title("여름 이벤트")
            .startAt(Instant.parse("2026-09-01T00:00:00Z"))
            .endAt(Instant.parse("2026-09-30T00:00:00Z"))
            .winnerCount(3)
            .status(EventStatus.OPEN)
            .build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(event, "eventId", 1L);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 처음_적중하면_요청_밖에서_다시_읽는다() {
        EventQueryService service = service(submitted::add, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        assertThat(service.getCachedEvent(1L)).isSameAs(cached);
        assertThat(submitted).hasSize(1);
        verify(eventRepository, never()).findById(1L);

        submitted.get(0).run();

        verify(eventRepository).findById(1L);
        verify(eventCache).save(any());
        assertThat(loads("refresh")).isEqualTo(1);
    }

    @Test
    void 갱신_간격_안에서는_다시_읽지_않는다() {
        EventQueryService service = service(Runnable::run, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        service.getCachedEvent(1L);
        nanos.addAndGet(AFTER.minusMillis(1).toNanos());
        service.getCachedEvent(1L);

        assertThat(loads("refresh")).isEqualTo(1);

        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        service.getCachedEvent(1L);

        assertThat(loads("refresh")).isEqualTo(2);
    }

    @Test
    void 미스로_읽은_뒤에는_갱신_간격이_지나야_다시_읽는다() {
        EventQueryService service = service(Runnable::run, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.empty(), Optional.of(cached));

        service.getCachedEvent(1L);
        service.getCachedEvent(1L);

        assertThat(loads("single_flight")).isEqualTo(1);
        assertThat(loads("refresh")).isZero();
    }

    @Test
    void 마감이_지난_이벤트는_다시_읽지_않는다() {
        EventQueryService service = service(submitted::add, true, "2026-09-30T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        service.getCachedEvent(1L);

        assertThat(submitted).isEmpty();
    }

    @Test
    void 꺼져_있으면_다시_읽지_않는다() {
        EventQueryService service = service(submitted::add, false, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        service.getCachedEvent(1L);

        assertThat(submitted).isEmpty();
    }

    @Test
    void 실행기가_가득_차면_버리고_센다() {
        EventQueryService service = service(task -> {
            throw new RejectedExecutionException("full");
        }, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        assertThat(service.getCachedEvent(1L)).isSameAs(cached);
        assertThat(registry.get("cking.event.cache.refresh.rejected").counter().count()).isEqualTo(1);
    }

    @Test
    void 갱신_중에_캐시가_비면_미스는_갱신_결과를_기다린다() throws Exception {
        EventQueryService service = service(submitted::add, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.of(cached), Optional.empty());
        when(eventRepository.findById(1L)).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(event);
        });
        service.getCachedEvent(1L);
        Future<?> refresh = executor.submit(submitted.get(0));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

        Future<CachedEvent> miss = executor.submit(() -> service.getCachedEvent(1L));
        awaitJoining();
        release.countDown();
        refresh.get(5, TimeUnit.SECONDS);
        miss.get(5, TimeUnit.SECONDS);

        verify(eventRepository, times(1)).findById(1L);
        assertThat(loads("refresh")).isEqualTo(1);
        assertThat(loads("single_flight")).isZero();
        assertThat(registry.get("cking.event.cache.load.wait").timer().count()).isEqualTo(1);
    }

    @Test
    void 진행_중인_적재가_있으면_갱신을_건너뛴다() throws Exception {
        EventQueryService service = service(submitted::add, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.empty(), Optional.of(cached));
        when(eventRepository.findById(1L)).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(event);
        });
        Future<CachedEvent> miss = executor.submit(() -> service.getCachedEvent(1L));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

        nanos.addAndGet(AFTER.toNanos());
        service.getCachedEvent(1L);
        submitted.forEach(Runnable::run);
        release.countDown();
        miss.get(5, TimeUnit.SECONDS);

        verify(eventRepository, times(1)).findById(1L);
        assertThat(loads("refresh")).isZero();
    }

    @Test
    void 동시에_적중해도_갱신은_한_번만_맡는다() throws Exception {
        EventQueryService service = service(submitted::add, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CachedEvent>> hits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hits.add(executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.getCachedEvent(1L);
            }));
        }

        start.countDown();
        for (Future<CachedEvent> hit : hits) {
            assertThat(hit.get(5, TimeUnit.SECONDS)).isSameAs(cached);
        }

        assertThat(submitted).hasSize(1);
    }

    @Test
    void 갱신이_실패하면_세고_다음_미스는_다시_읽는다() {
        EventQueryService service = service(Runnable::run, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached), Optional.empty());
        when(eventRepository.findById(1L)).thenThrow(new IllegalStateException("db down")).thenReturn(Optional.of(event));

        assertThat(service.getCachedEvent(1L)).isSameAs(cached);
        service.getCachedEvent(1L);

        assertThat(registry.get("cking.event.cache.refresh.failed").counter().count()).isEqualTo(1);
        assertThat(loads("single_flight")).isEqualTo(1);
        verify(eventCache, never()).evict(1L);
    }

    @Test
    void 갱신에서_이벤트가_사라졌으면_캐시를_지운다() {
        EventQueryService service = service(Runnable::run, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));
        when(eventRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.getCachedEvent(1L)).isSameAs(cached);

        verify(eventCache).evict(1L);
        assertThat(registry.get("cking.event.cache.refresh.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void 트랜잭션_안에서_읽은_뒤에도_갱신_간격이_지나야_다시_읽는다() {
        EventQueryService service = service(submitted::add, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.empty(), Optional.of(cached));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.getCachedEvent(1L);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        service.getCachedEvent(1L);

        assertThat(loads("in_transaction")).isEqualTo(1);
        assertThat(submitted).isEmpty();
    }

    @Test
    void 거절된_뒤에는_갱신_간격이_지나야_다시_맡는다() {
        AtomicInteger attempts = new AtomicInteger();
        EventQueryService service = service(task -> {
            attempts.incrementAndGet();
            throw new RejectedExecutionException("full");
        }, true, "2026-09-15T00:00:00Z");
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        service.getCachedEvent(1L);
        service.getCachedEvent(1L);
        assertThat(attempts).hasValue(1);

        nanos.addAndGet(AFTER.toNanos());
        service.getCachedEvent(1L);

        assertThat(attempts).hasValue(2);
        assertThat(registry.get("cking.event.cache.refresh.rejected").counter().count()).isEqualTo(2);
    }

    private EventQueryService service(Executor refreshExecutor, boolean enabled, String now) {
        EventCacheRefresher refresher = new EventCacheRefresher(registry, refreshExecutor, enabled, AFTER, nanos::get);
        return new EventQueryService(
                eventRepository, Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
                mock(TicketBalanceQueryService.class), mock(CommonTicketBalanceQueryService.class), eventCache,
                mock(EventListCache.class), mock(EventCountsCache.class), mock(MemberRepository.class),
                new EventCacheLoader(eventRepository, creatorQueryService, eventCache), new EventCacheMetrics(registry),
                refresher, TransactionOperations.withoutTransaction());
    }

    private CachedEvent cached() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));
        CachedEvent cached = new EventCacheLoader(eventRepository, creatorQueryService, mock(EventCache.class)).load(1L);
        clearInvocations(eventRepository);
        return cached;
    }

    private long loads(String mode) {
        return registry.get("cking.event.cache.load").tag("mode", mode).timer().count();
    }

    private void awaitJoining() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean joining = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getState() == Thread.State.WAITING)
                    .anyMatch(thread -> {
                        for (StackTraceElement frame : thread.getStackTrace()) {
                            if (frame.getMethodName().equals("awaitLoad")) {
                                return true;
                            }
                        }
                        return false;
                    });
            if (joining) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("미스 요청이 갱신 결과를 기다리는 상태가 되지 않았습니다.");
    }
}

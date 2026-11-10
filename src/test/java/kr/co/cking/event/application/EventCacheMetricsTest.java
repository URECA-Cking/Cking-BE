package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.CommonTicketBalanceQueryService;
import kr.co.cking.ticket.application.TicketBalanceQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EventCacheMetricsTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final EventCache eventCache = mock(EventCache.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final EventQueryService service = new EventQueryService(
            eventRepository, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC),
            mock(TicketBalanceQueryService.class), mock(CommonTicketBalanceQueryService.class), eventCache,
            mock(EventListCache.class), mock(EventCountsCache.class), mock(MemberRepository.class),
            new EventCacheLoader(eventRepository, creatorQueryService, eventCache), new EventCacheMetrics(registry));
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private final Event event = Event.builder()
            .creatorId(7L)
            .title("여름 이벤트")
            .startAt(Instant.parse("2026-09-01T00:00:00Z"))
            .endAt(Instant.parse("2026-09-30T00:00:00Z"))
            .winnerCount(3)
            .status(EventStatus.OPEN)
            .build();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 캐시_적중은_적중으로만_센다() {
        CachedEvent cached = cached();
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));

        service.getCachedEvent(1L);

        assertThat(lookups("hit")).isEqualTo(1);
        assertThat(lookups("miss")).isZero();
        assertThat(loads("single_flight")).isZero();
    }

    @Test
    void 트랜잭션_밖의_미스는_직접_읽은_적재로_센다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        service.getCachedEvent(1L);

        assertThat(lookups("miss")).isEqualTo(1);
        assertThat(loads("single_flight")).isEqualTo(1);
        assertThat(loads("in_transaction")).isZero();
        assertThat(waits()).isZero();
    }

    @Test
    void 트랜잭션_안의_미스는_트랜잭션_안_적재로_센다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.getCachedEvent(1L);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(loads("in_transaction")).isEqualTo(1);
        assertThat(loads("single_flight")).isZero();
    }

    @Test
    void 진행_중인_적재를_기다린_요청은_대기로_센다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(event);
        });
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        Future<CachedEvent> leader = executor.submit(() -> service.getCachedEvent(1L));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        Future<CachedEvent> follower = executor.submit(() -> service.getCachedEvent(1L));
        awaitJoining();
        release.countDown();
        leader.get(5, TimeUnit.SECONDS);
        follower.get(5, TimeUnit.SECONDS);

        assertThat(loads("single_flight")).isEqualTo(1);
        assertThat(waits()).isEqualTo(1);
        assertThat(lookups("miss")).isEqualTo(2);
    }

    @Test
    void 실패한_적재도_시간을_남긴다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCachedEvent(1L)).isInstanceOf(BusinessException.class);

        assertThat(loads("single_flight")).isEqualTo(1);
    }

    private CachedEvent cached() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));
        return new EventCacheLoader(eventRepository, creatorQueryService, mock(EventCache.class)).load(1L);
    }

    private double lookups(String result) {
        return registry.get("cking.event.cache.lookup").tag("result", result).counter().count();
    }

    private long loads(String mode) {
        return registry.get("cking.event.cache.load").tag("mode", mode).timer().count();
    }

    private long waits() {
        return registry.get("cking.event.cache.load.wait").timer().count();
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
        throw new AssertionError("두 번째 요청이 적재 결과를 기다리는 상태가 되지 않았습니다.");
    }
}

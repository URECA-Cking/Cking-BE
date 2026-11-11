package kr.co.cking.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EventQueryServiceCachedEventLoadTest {

    private static final int CALLERS = 8;

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final EventCache eventCache = mock(EventCache.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final EventQueryService service = new EventQueryService(
            eventRepository, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC),
            mock(TicketBalanceQueryService.class), mock(CommonTicketBalanceQueryService.class), eventCache,
            mock(EventListCache.class), mock(EventCountsCache.class), mock(MemberRepository.class), new EventCacheLoader(eventRepository, creatorQueryService, eventCache), EventCacheMetrics.detached(), EventCacheRefresher.disabled(), TransactionOperations.withoutTransaction());
    private final ExecutorService executor = Executors.newFixedThreadPool(CALLERS);

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
    void 캐시가_빈_순간_함께_들어온_요청은_DB를_한_번만_읽고_같은_결과를_쓴다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(event);
        });
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        List<Future<CachedEvent>> results = callConcurrently();
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        awaitOthersWaiting();
        release.countDown();

        for (Future<CachedEvent> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS).ownerMemberId()).isEqualTo(200L);
        }
        verify(eventRepository, times(1)).findById(1L);
        verify(eventCache, times(1)).save(any());
    }

    @Test
    void 읽기가_실패하면_기다리던_요청도_같은_예외를_받는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        List<Future<CachedEvent>> results = callConcurrently();
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        awaitOthersWaiting();
        release.countDown();

        for (Future<CachedEvent> result : results) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
        }
        verify(eventRepository, times(1)).findById(1L);
    }

    @Test
    void 읽기에서_Error가_나도_기다리던_요청이_풀린다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new StackOverflowError("읽기 실패");
        });

        List<Future<CachedEvent>> results = callConcurrently();
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        awaitOthersWaiting();
        release.countDown();

        for (Future<CachedEvent> result : results) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(StackOverflowError.class);
        }
    }

    @Test
    void 트랜잭션_안의_호출은_진행_중인_읽기를_기다리지_않고_직접_읽는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L))
                .thenAnswer(invocation -> {
                    loading.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return Optional.of(event);
                })
                .thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        Future<CachedEvent> outside = executor.submit(() -> service.getCachedEvent(1L));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        Future<CachedEvent> inside = executor.submit(() -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                return service.getCachedEvent(1L);
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        });

        assertThat(inside.get(2, TimeUnit.SECONDS).ownerMemberId()).isEqualTo(200L);
        assertThat(outside).isNotDone();
        release.countDown();
        assertThat(outside.get(5, TimeUnit.SECONDS).ownerMemberId()).isEqualTo(200L);
        verify(eventRepository, times(2)).findById(1L);
    }

    @Test
    void 앞선_읽기가_끝난_뒤_캐시가_또_비어_있으면_다시_읽는다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        service.getCachedEvent(1L);
        service.getCachedEvent(1L);

        verify(eventRepository, times(2)).findById(1L);
    }

    private List<Future<CachedEvent>> callConcurrently() {
        List<Future<CachedEvent>> results = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            results.add(executor.submit(() -> service.getCachedEvent(1L)));
        }
        return results;
    }

    // 읽는 요청 하나를 뺀 나머지가 모두 그 결과를 기다리는 상태가 된 뒤에 읽기를 끝내야 묶임을 확인할 수 있다.
    private void awaitOthersWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long waiting = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getState() == Thread.State.WAITING)
                    .filter(thread -> isJoiningLoad(thread.getStackTrace()))
                    .count();
            if (waiting == CALLERS - 1) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("나머지 요청이 읽기 결과를 기다리는 상태가 되지 않았습니다.");
    }

    private static boolean isJoiningLoad(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            if (frame.getMethodName().equals("awaitLoad")) {
                return true;
            }
        }
        return false;
    }
}

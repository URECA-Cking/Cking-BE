package kr.co.cking.event.application;

import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.CachedEventPage;
import kr.co.cking.event.application.dto.EventCounts;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.CommonTicketBalanceQueryService;
import kr.co.cking.ticket.application.TicketBalanceQueryService;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventQueryServiceRedisOutsideTransactionTest {

    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final RecordingTransaction transaction = new RecordingTransaction();
    private final List<String> calls = new ArrayList<>();
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final TicketBalanceQueryService ticketBalanceQueryService = mock(TicketBalanceQueryService.class);
    private final CommonTicketBalanceQueryService commonTicketBalanceQueryService = mock(CommonTicketBalanceQueryService.class);
    private final EventCache eventCache = mock(EventCache.class);
    private final EventListCache eventListCache = mock(EventListCache.class);
    private final EventCountsCache eventCountsCache = mock(EventCountsCache.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final EventQueryService service = new EventQueryService(eventRepository, Clock.fixed(now, ZoneOffset.UTC),
            ticketBalanceQueryService, commonTicketBalanceQueryService, eventCache, eventListCache, eventCountsCache,
            memberRepository, new EventCacheLoader(eventRepository, creatorQueryService, eventCache),
            EventCacheMetrics.detached(), EventCacheRefresher.disabled(), transaction);

    private final Event event = Event.builder()
            .creatorId(7L)
            .title("여름 이벤트")
            .startAt(Instant.parse("2026-09-01T00:00:00Z"))
            .endAt(Instant.parse("2026-09-30T00:00:00Z"))
            .winnerCount(3)
            .status(EventStatus.OPEN)
            .build();

    @Test
    void 로그인_상세는_Redis_캐시를_트랜잭션_밖에서_읽고_잔액_조회만_트랜잭션_하나에서_한다() {
        when(eventCache.find(1L)).thenAnswer(invocation -> record("redis:event", Optional.of(CachedEvent.from(event))));
        when(memberRepository.existsById(100L)).thenAnswer(invocation -> record("db:member", true));
        when(ticketBalanceQueryService.getBalance(7L, 100L)).thenAnswer(invocation -> record("db:ticket", 42L));
        when(commonTicketBalanceQueryService.getBalanceDetail(100L))
                .thenAnswer(invocation -> record("db:common", new CommonTicketBalanceResponse(100L, 9L, null)));

        service.getEvent(1L, 100L);

        assertThat(calls).containsExactly("db:member@out", "redis:event@out", "db:ticket@in", "db:common@in");
        assertThat(transaction.executions).isEqualTo(1);
    }

    @Test
    void 비로그인_상세가_캐시에_있으면_트랜잭션을_열지_않는다() {
        when(eventCache.find(1L)).thenAnswer(invocation -> record("redis:event", Optional.of(CachedEvent.from(event))));

        service.getEvent(1L, null);

        assertThat(calls).containsExactly("redis:event@out");
        assertThat(transaction.executions).isZero();
    }

    @Test
    void 로그인_목록은_회원_확인과_공통_잔액을_캐시_확인_전에_트랜잭션_하나에서_읽는다() {
        when(memberRepository.existsById(100L)).thenAnswer(invocation -> record("db:member", true));
        when(commonTicketBalanceQueryService.getBalanceDetail(100L))
                .thenAnswer(invocation -> record("db:common", new CommonTicketBalanceResponse(100L, 9L, null)));
        when(eventListCache.find(any(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> record("redis:find", Optional.of(new CachedEventPage(List.of(CachedEvent.from(event)), 1))));

        service.getEvents(7L, DisplayStatus.IN_PROGRESS, 100L, 0, 20);

        assertThat(calls).containsExactly("db:member@in", "db:common@in", "redis:find@out");
        assertThat(transaction.executions).isEqualTo(1);
    }

    @Test
    void 목록이_캐시에_없으면_DB에서_읽은_뒤_Redis_저장은_트랜잭션_밖에서_한다() {
        when(eventListCache.find(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> record("redis:find", Optional.empty()));
        when(memberRepository.existsById(100L)).thenAnswer(invocation -> record("db:member", true));
        when(eventRepository.search(any(), any(DisplayStatus.class), any(), any())).thenAnswer(invocation -> record("db:search", new PageImpl<>(List.of(event))));
        when(commonTicketBalanceQueryService.getBalanceDetail(100L))
                .thenAnswer(invocation -> record("db:common", new CommonTicketBalanceResponse(100L, 9L, null)));
        doAnswer(invocation -> record("redis:save", null))
                .when(eventListCache).save(any(), any(), anyInt(), anyInt(), any(CachedEventPage.class));

        service.getEvents(7L, DisplayStatus.IN_PROGRESS, 100L, 0, 20);

        assertThat(calls).containsExactly("db:member@in", "db:common@in", "redis:find@out", "db:search@in", "redis:save@out");
        assertThat(transaction.executions).isEqualTo(2);
    }

    @Test
    void 비로그인_목록이_캐시에_있으면_트랜잭션을_열지_않는다() {
        when(eventListCache.find(any(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> record("redis:find", Optional.of(new CachedEventPage(List.of(CachedEvent.from(event)), 1))));

        service.getEvents(7L, DisplayStatus.IN_PROGRESS, null, 0, 20);

        assertThat(calls).containsExactly("redis:find@out");
        assertThat(transaction.executions).isZero();
    }

    @Test
    void 상태별_개수가_캐시에_없으면_DB에서_센_뒤_Redis_저장은_트랜잭션_밖에서_한다() {
        EventRepository.DisplayStatusCounts counts = mock(EventRepository.DisplayStatusCounts.class);
        when(counts.getUpcoming()).thenReturn(1L);
        when(counts.getInProgress()).thenReturn(2L);
        when(counts.getClosed()).thenReturn(3L);
        when(eventCountsCache.find()).thenAnswer(invocation -> record("redis:find", Optional.empty()));
        when(eventRepository.countByDisplayStatus(now)).thenAnswer(invocation -> record("db:count", counts));
        when(eventRepository.findEarliestOpenEndAt(now)).thenAnswer(invocation -> record("db:earliest", Optional.empty()));
        doAnswer(invocation -> record("redis:save", null))
                .when(eventCountsCache).save(any(EventCounts.class), eq(null));

        EventCounts result = service.getEventCounts();

        assertThat(result).isEqualTo(new EventCounts(6L, 1L, 2L, 3L));
        assertThat(calls).containsExactly("redis:find@out", "db:count@in", "db:earliest@in", "redis:save@out");
    }

    private <T> T record(String call, T result) {
        calls.add(call + (transaction.active ? "@in" : "@out"));
        return result;
    }

    private static final class RecordingTransaction implements TransactionOperations {

        private boolean active;
        private int executions;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            active = true;
            try {
                TransactionStatus status = new SimpleTransactionStatus();
                return action.doInTransaction(status);
            } finally {
                active = false;
            }
        }
    }
}

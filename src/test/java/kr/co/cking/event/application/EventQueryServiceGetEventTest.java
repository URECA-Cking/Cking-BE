package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.EventDetail;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.CommonTicketBalanceQueryService;
import kr.co.cking.ticket.application.TicketBalanceQueryService;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventQueryServiceGetEventTest {

    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final TicketBalanceQueryService ticketBalanceQueryService = mock(TicketBalanceQueryService.class);
    private final CommonTicketBalanceQueryService commonTicketBalanceQueryService = mock(CommonTicketBalanceQueryService.class);
    private final EventCache eventCache = mock(EventCache.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final EventQueryService service =
            new EventQueryService(eventRepository, clock, ticketBalanceQueryService, commonTicketBalanceQueryService, eventCache, mock(EventListCache.class), mock(EventCountsCache.class), memberRepository, creatorQueryService);

    @BeforeEach
    void setUp() {
        when(memberRepository.existsById(any())).thenReturn(true);
        when(commonTicketBalanceQueryService.getBalanceDetail(any()))
                .thenReturn(new CommonTicketBalanceResponse(100L, 0L, null));
    }

    private final Event event = Event.builder()
            .creatorId(7L)
            .title("여름 이벤트")
            .startAt(Instant.parse("2026-09-01T00:00:00Z"))
            .endAt(Instant.parse("2026-09-30T00:00:00Z"))
            .winnerCount(3)
            .status(EventStatus.OPEN)
            .build();

    @Test
    void 이벤트_상세에_내_잔액을_포함한다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));
        when(ticketBalanceQueryService.getBalance(7L, 100L)).thenReturn(42L);
        when(commonTicketBalanceQueryService.getBalanceDetail(100L))
                .thenReturn(new CommonTicketBalanceResponse(100L, 9L, null));

        EventDetail detail = service.getEvent(1L, 100L);

        assertThat(detail.myTicketBalance()).isEqualTo(42L);
        assertThat(detail.myCommonTicketBalance()).isEqualTo(9L);
        assertThat(detail.displayStatus()).isEqualTo(DisplayStatus.IN_PROGRESS);
    }

    /** 비로그인 상세는 공개 Event만 조회하고 Member·응모권 서비스에 접근하지 않는다. */
    @Test
    void 비로그인_이벤트_상세는_잔액_0을_반환한다() {
        when(eventCache.find(1L)).thenReturn(Optional.of(CachedEvent.from(event)));

        EventDetail detail = service.getEvent(1L, null);

        assertThat(detail.title()).isEqualTo("여름 이벤트");
        assertThat(detail.myTicketBalance()).isZero();
        assertThat(detail.myCommonTicketBalance()).isZero();
        verifyNoInteractions(memberRepository, ticketBalanceQueryService, commonTicketBalanceQueryService);
    }

    /** 같은 공개 캐시를 사용해도 비로그인 잔액이 로그인 사용자의 응답에 섞이지 않는다. */
    @Test
    void 공개_캐시의_상세에_로그인_잔액을_요청별로_붙인다() {
        when(eventCache.find(1L)).thenReturn(Optional.of(CachedEvent.from(event)));
        when(ticketBalanceQueryService.getBalance(7L, 100L)).thenReturn(42L);
        when(commonTicketBalanceQueryService.getBalanceDetail(100L))
                .thenReturn(new CommonTicketBalanceResponse(100L, 9L, null));

        EventDetail guest = service.getEvent(1L, null);
        EventDetail member = service.getEvent(1L, 100L);

        assertThat(guest.myTicketBalance()).isZero();
        assertThat(guest.myCommonTicketBalance()).isZero();
        assertThat(member.myTicketBalance()).isEqualTo(42L);
        assertThat(member.myCommonTicketBalance()).isEqualTo(9L);
        verify(eventRepository, never()).findById(any());
    }

    @Test
    void 존재하지_않는_userId면_예외가_발생하고_이벤트를_조회하지_않는다() {
        when(memberRepository.existsById(100L)).thenReturn(false);

        assertThatThrownBy(() -> service.getEvent(1L, 100L))
                .isInstanceOf(BusinessException.class);
        verify(eventCache, never()).find(any());
    }

    @Test
    void 없는_이벤트를_조회하면_예외가_발생한다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvent(1L, 100L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 승인전_이벤트를_조회하면_예외가_발생한다() {
        Event draft = Event.builder()
                .creatorId(7L)
                .title("초안 이벤트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-30T00:00:00Z"))
                .winnerCount(3)
                .status(EventStatus.DRAFT)
                .build();
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getEvent(1L, 100L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 보관된_공개_이벤트는_상세_조회에서_제외된다() {
        Event archived = Event.builder()
                .creatorId(7L)
                .title("보관 이벤트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-30T00:00:00Z"))
                .winnerCount(3)
                .status(EventStatus.PUBLISHED)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(archived, "archivedAt", now);
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.getEvent(1L, 100L))
                .isInstanceOf(BusinessException.class);

        verify(eventCache, never()).save(any());
    }

    @Test
    void 캐시에_있으면_레포지토리를_거치지_않는다() {
        CachedEvent cached = CachedEvent.from(event, 200L);
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));
        when(ticketBalanceQueryService.getBalance(7L, 100L)).thenReturn(5L);

        EventDetail detail = service.getEvent(1L, 100L);

        assertThat(detail.myTicketBalance()).isEqualTo(5L);
        assertThat(detail.title()).isEqualTo("여름 이벤트");
        verify(eventRepository, never()).findById(any());
        verifyNoInteractions(creatorQueryService);
    }

    @Test
    void 캐시에_없으면_조회후_캐시에_적재한다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(creatorQueryService.findOwnerMemberId(7L)).thenReturn(Optional.of(200L));

        service.getEvent(1L, 100L);

        verify(eventCache).save(CachedEvent.from(event, 200L));
    }

    @Test
    void 운영용_조회는_DRAFT_이벤트도_반환한다() {
        Event draft = Event.builder()
                .creatorId(7L)
                .title("초안 이벤트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-30T00:00:00Z"))
                .winnerCount(3)
                .status(EventStatus.DRAFT)
                .build();
        when(eventRepository.findById(1L)).thenReturn(Optional.of(draft));

        Event found = service.getEventForOperation(1L);

        assertThat(found.getStatus()).isEqualTo(EventStatus.DRAFT);
        verify(eventCache, never()).find(any());
    }

    @Test
    void invalidate하면_캐시를_지운다() {
        service.invalidate(1L);

        verify(eventCache).evict(1L);
    }
}

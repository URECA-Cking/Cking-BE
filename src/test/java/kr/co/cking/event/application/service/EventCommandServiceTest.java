package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;

@ExtendWith(MockitoExtension.class)
class EventCommandServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T00:05:00Z");

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventQueryService eventQueryService;

    private EventCommandService eventCommandService;

    @BeforeEach
    void setUp() {
        eventCommandService = new EventCommandService(eventRepository, eventQueryService, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void startClosing은_OPEN_이벤트를_CLOSING으로_전이하고_캐시를_무효화한다() {
        Event event = eventOf(EventStatus.OPEN);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.startClosing(1L, "123-0");

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getCutoffStreamId()).isEqualTo("123-0");
        verify(eventQueryService).invalidate(1L);
    }

    @Test
    void startClosing은_동일_cutoff로_이미_CLOSING이면_멱등하게_아무것도_하지_않는다() {
        Event event = eventOf(EventStatus.OPEN);
        event.startClosing("123-0");
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.startClosing(1L, "123-0");

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSING);
        verify(eventQueryService, never()).invalidate(1L);
    }

    @Test
    void startClosing은_OPEN이_아닌_잘못된_상태면_INVALID_STATE를_던진다() {
        Event event = eventOf(EventStatus.DRAFT);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.startClosing(1L, "123-0"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        verify(eventQueryService, never()).invalidate(1L);
    }

    @Test
    void completeClosing은_CLOSING_이벤트를_CLOSED로_전이하고_캐시를_무효화한다() {
        Event event = eventOf(EventStatus.CLOSING);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.completeClosing(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(event.getClosedAt()).isEqualTo(FIXED_NOW);
        verify(eventQueryService).invalidate(1L);
    }

    @Test
    void completeClosing은_이미_CLOSED면_멱등하게_아무것도_하지_않는다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.completeClosing(1L);

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
        verify(eventQueryService, never()).invalidate(1L);
    }

    @Test
    void completeClosing은_CLOSING이_아닌_잘못된_상태면_INVALID_STATE를_던진다() {
        Event event = eventOf(EventStatus.OPEN);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.completeClosing(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        verify(eventQueryService, never()).invalidate(1L);
    }

    private static Event eventOf(EventStatus status) {
        return Event.builder()
                .status(status)
                .startAt(Instant.parse("2026-09-10T00:00:00Z"))
                .endAt(Instant.parse("2026-09-20T00:00:00Z"))
                .build();
    }
}

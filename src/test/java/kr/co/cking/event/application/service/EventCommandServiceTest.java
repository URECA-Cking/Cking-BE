package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;

@ExtendWith(MockitoExtension.class)
class EventCommandServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventQueryService eventQueryService;

    @InjectMocks
    private EventCommandService eventCommandService;

    @Test
    void startClosing은_OPEN_이벤트를_CLOSING으로_전이하고_캐시를_무효화한다() {
        Event event = eventOf(EventStatus.OPEN);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        EventStatus result = eventCommandService.startClosing(1L, "123-0");

        assertThat(result).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getCutoffStreamId()).isEqualTo("123-0");
        verify(eventQueryService).invalidate(1L);
    }

    @Test
    void startClosing은_이미_CLOSING이면_상태_변경_없이_CLOSING을_반환한다() {
        Event event = eventOf(EventStatus.CLOSING);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        EventStatus result = eventCommandService.startClosing(1L, "123-0");

        assertThat(result).isEqualTo(EventStatus.CLOSING);
        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSING);
        verify(eventQueryService, never()).invalidate(1L);
    }

    @Test
    void startClosing은_이미_CLOSED이면_상태_변경_없이_CLOSED를_반환한다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        EventStatus result = eventCommandService.startClosing(1L, "123-0");

        assertThat(result).isEqualTo(EventStatus.CLOSED);
        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
        verify(eventQueryService, never()).invalidate(1L);
    }

    @Test
    void startClosing은_마감_불가능한_상태면_INVALID_STATE를_던진다() {
        Event event = eventOf(EventStatus.SCHEDULED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        assertThatThrownBy(() -> eventCommandService.startClosing(1L, "123-0"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_STATE);
        verify(eventQueryService, never()).invalidate(1L);
    }

    @Test
    void completeClosing은_CLOSING_이벤트를_CLOSED로_전이하고_캐시를_무효화한다() {
        Event event = eventOf(EventStatus.CLOSING);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));
        Instant closedAt = Instant.parse("2026-09-20T00:05:00Z");

        eventCommandService.completeClosing(1L, closedAt);

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
        assertThat(event.getClosedAt()).isEqualTo(closedAt);
        verify(eventQueryService).invalidate(1L);
    }

    @Test
    void completeClosing은_이미_CLOSING이_아니면_아무것도_하지_않는다() {
        Event event = eventOf(EventStatus.CLOSED);
        when(eventRepository.findByEventId(1L)).thenReturn(java.util.Optional.of(event));

        eventCommandService.completeClosing(1L, Instant.now());

        assertThat(event.getStatus()).isEqualTo(EventStatus.CLOSED);
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

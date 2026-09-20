package kr.co.cking.event.application;

import kr.co.cking.event.application.service.EventGateLoader;
import kr.co.cking.event.application.service.EventOpenedEvent;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import java.util.Optional;
import kr.co.cking.event.application.service.EventClosingStateChangedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventCacheInvalidationListenerTest {

    @Test
    void 마감_상태전이_커밋후_이벤트_캐시를_무효화한다() throws Exception {
        EventQueryService eventQueryService = mock(EventQueryService.class);
        EventGateLoader eventGateLoader = mock(EventGateLoader.class);
        EventRepository eventRepository = mock(EventRepository.class);
        EventCacheInvalidationListener listener = new EventCacheInvalidationListener(eventQueryService, eventGateLoader, eventRepository);

        listener.invalidateAfterClosingTransition(new EventClosingStateChangedEvent(1L));

        verify(eventQueryService).invalidate(1L);
    }

    @Test
    void 캐시_무효화_실패는_OPEN_전이_후속_처리를_실패시키지_않는다() {
        EventQueryService eventQueryService = mock(EventQueryService.class);
        EventGateLoader eventGateLoader = mock(EventGateLoader.class);
        EventRepository eventRepository = mock(EventRepository.class);
        doThrow(new RuntimeException("Redis unavailable")).when(eventQueryService).invalidate(1L);
        EventCacheInvalidationListener listener = new EventCacheInvalidationListener(eventQueryService, eventGateLoader, eventRepository);

        assertThatCode(() -> listener.invalidateAfterOpen(new EventOpenedEvent(1L)))
                .doesNotThrowAnyException();

        verify(eventQueryService).invalidate(1L);
    }

    @Test
    void OPEN_전이_커밋후_Gate를_적재하고_적재_실패도_예외로_번지지_않는다() {
        EventQueryService eventQueryService = mock(EventQueryService.class);
        EventGateLoader eventGateLoader = mock(EventGateLoader.class);
        EventRepository eventRepository = mock(EventRepository.class);
        Event event = mock(Event.class);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        doThrow(new RuntimeException("Redis unavailable")).when(eventGateLoader).load(event);
        EventCacheInvalidationListener listener =
                new EventCacheInvalidationListener(eventQueryService, eventGateLoader, eventRepository);

        assertThatCode(() -> listener.invalidateAfterOpen(new EventOpenedEvent(1L)))
                .doesNotThrowAnyException();

        verify(eventGateLoader).load(event);
    }
}

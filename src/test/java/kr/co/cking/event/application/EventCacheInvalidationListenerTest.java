package kr.co.cking.event.application;

import kr.co.cking.event.application.service.EventOpenedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventCacheInvalidationListenerTest {

    @Test
    void 캐시_무효화_실패는_OPEN_전이_후속_처리를_실패시키지_않는다() {
        EventQueryService eventQueryService = mock(EventQueryService.class);
        doThrow(new RuntimeException("Redis unavailable")).when(eventQueryService).invalidate(1L);
        EventCacheInvalidationListener listener = new EventCacheInvalidationListener(eventQueryService);

        assertThatCode(() -> listener.invalidateAfterOpen(new EventOpenedEvent(1L)))
                .doesNotThrowAnyException();

        verify(eventQueryService).invalidate(1L);
    }
}

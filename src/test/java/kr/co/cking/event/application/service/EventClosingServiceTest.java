package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.co.cking.event.domain.EventStatus;

@ExtendWith(MockitoExtension.class)
class EventClosingServiceTest {

    @Mock
    private EventCutoffBarrier eventCutoffBarrier;

    @Mock
    private EventCommandService eventCommandService;

    @InjectMocks
    private EventClosingService eventClosingService;

    @Test
    void Gate와_cutoff를_확정한_뒤_CLOSING_전이를_요청한다() {
        when(eventCutoffBarrier.close(1L)).thenReturn("123-0");
        when(eventCommandService.startClosing(1L, "123-0")).thenReturn(EventStatus.CLOSING);

        EventClosingService.ClosingResult result = eventClosingService.startClosing(1L);

        InOrder inOrder = inOrder(eventCutoffBarrier, eventCommandService);
        inOrder.verify(eventCutoffBarrier).close(1L);
        inOrder.verify(eventCommandService).startClosing(1L, "123-0");
        assertThat(result).isEqualTo(new EventClosingService.ClosingResult(1L, EventStatus.CLOSING));
    }
}

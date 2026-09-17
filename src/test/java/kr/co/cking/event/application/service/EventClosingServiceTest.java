package kr.co.cking.event.application.service;

import kr.co.cking.event.domain.EventStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class EventClosingServiceTest {

    @Test
    void startClosingDelegatesGateAndStateTransitionToSystemTwoComponents() {
        EventCutoffBarrier eventCutoffBarrier = mock(EventCutoffBarrier.class);
        EventCommandService eventCommandService = mock(EventCommandService.class);
        given(eventCutoffBarrier.close(10L)).willReturn("1-0");

        EventClosingService.ClosingResult result = new EventClosingService(eventCutoffBarrier, eventCommandService)
                .startClosing(10L);

        assertThat(result).isEqualTo(new EventClosingService.ClosingResult(10L, EventStatus.CLOSING));
        InOrder inOrder = inOrder(eventCutoffBarrier, eventCommandService);
        inOrder.verify(eventCutoffBarrier).close(10L);
        inOrder.verify(eventCommandService).startClosing(10L, "1-0");
    }
}

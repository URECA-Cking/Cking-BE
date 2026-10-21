package kr.co.cking.notification.scheduler;

import kr.co.cking.notification.application.TicketRewardNotificationService;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class TicketRewardNotificationSchedulerTest {
    @Test void 실패와_재시도_예약_실패도_뒤쪽_정상건을_막지_않는다() {
        var store = mock(RewardNotificationLedgerStore.class);
        var service = mock(TicketRewardNotificationService.class);
        when(store.findDue(false, 20)).thenReturn(List.of(1L, 2L));
        when(store.findDue(true, 20)).thenReturn(List.of(3L));
        doThrow(new IllegalStateException()).when(service).create(false, 1);
        doThrow(new IllegalStateException()).when(service).retry(false, 1);
        new TicketRewardNotificationScheduler(store, service, 20).deliver();
        verify(service).create(false, 2);
        verify(service).create(true, 3);
        verify(service).retry(false, 1);
    }

    @Test void 한_원장_조회_실패도_다른_원장_처리를_막지_않는다() {
        var store = mock(RewardNotificationLedgerStore.class);
        var service = mock(TicketRewardNotificationService.class);
        when(store.findDue(false, 100)).thenThrow(new IllegalStateException());
        when(store.findDue(true, 100)).thenReturn(List.of(1L));
        new TicketRewardNotificationScheduler(store, service, Integer.MAX_VALUE).deliver();
        verify(service).create(true, 1);
    }
}

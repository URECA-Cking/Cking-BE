package kr.co.cking.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WinnerNotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private WinnerNotificationService winnerNotificationService;

    @Test
    @SuppressWarnings("unchecked")
    void INITIAL_Drawing의_Winner별로_최초_당첨_알림을_저장한다() {
        winnerNotificationService.createInitialWinnerNotifications(20L, 10L, List.of(
                new WinnerNotificationTarget(100L, 2L),
                new WinnerNotificationTarget(101L, 3L)
        ));

        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(
                Notification::getEventId,
                Notification::getDrawingId,
                Notification::getWinnerId,
                Notification::getMemberId,
                Notification::getType
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(20L, 10L, 100L, 2L, NotificationType.INITIAL_WINNER),
                org.assertj.core.groups.Tuple.tuple(20L, 10L, 101L, 3L, NotificationType.INITIAL_WINNER)
        );
    }
}

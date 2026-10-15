package kr.co.cking.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

    @Test
    @SuppressWarnings("unchecked")
    void REDRAW_Drawing의_Winner별로_재추첨_당첨_알림을_저장한다() {
        winnerNotificationService.createRedrawWinnerNotifications(20L, 10L, List.of(
                new WinnerNotificationTarget(100L, 2L),
                new WinnerNotificationTarget(101L, 3L)
        ));

        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Notification::getType)
                .containsOnly(NotificationType.REDRAW_WINNER);
    }

    @Test
    void 미당첨자_알림은_DB_일괄_INSERT로_저장하고_저장_건수를_반환한다() {
        when(notificationRepository.insertNonWinnerNotifications(
                eq(10L), eq("INITIAL_NON_WINNER"), eq("이벤트 결과 발표"), anyString(), any(Instant.class)
        )).thenReturn(3);

        int created = winnerNotificationService.createNonWinnerNotifications(10L);

        assertThat(created).isEqualTo(3);
        verify(notificationRepository, never()).saveAll(any());
    }
}

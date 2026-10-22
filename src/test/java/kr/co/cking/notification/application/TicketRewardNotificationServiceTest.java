package kr.co.cking.notification.application;

import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketRewardNotificationServiceTest {
    private final RewardNotificationLedgerStore ledgers = mock(RewardNotificationLedgerStore.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final TicketRewardNotificationService service = new TicketRewardNotificationService(ledgers, notifications);

    @BeforeEach void savedId() {
        when(notifications.saveAndFlush(any())).thenAnswer(call -> {
            Notification notification = call.getArgument(0);
            ReflectionTestUtils.setField(notification, "notificationId", 100L);
            return notification;
        });
    }

    @ParameterizedTest
    @CsvSource({"true,ATTENDANCE,출석체크", "true,SIGNUP,웰컴 가입", "true,FOLLOW,웰컴 팔로우",
            "true,QUIZ,웰컴 퀴즈", "true,BONUS,웰컴 보너스", "false,LIKE,좋아요",
            "false,SHARE,공유", "false,YOUTUBE_SUBSCRIPTION,유튜브 구독", "false,AI_QUIZ,AI 퀴즈"})
    void 원장_보상_종류와_실제_수량으로_본문을_작성한다(boolean common, String reason, String label) {
        when(ledgers.lock(common, 1L)).thenReturn(Optional.of(new Target(1, 2, 7, reason, "크리에이터")));
        service.create(common, 1L);
        var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notifications).saveAndFlush(captor.capture());
        Notification notification = captor.getValue();
        assertThat(notification.getTitle()).isEqualTo("응모권 지급 완료");
        assertThat(notification.getType()).isEqualTo(common ? NotificationType.COMMON_TICKET_REWARDED : NotificationType.CREATOR_TICKET_REWARDED);
        assertThat(notification.getBody()).isEqualTo(label + " 보상으로 " + (common ? "공용" : "크리에이터 크리에이터 전용") + " 응모권 7장이 지급됐어요.");
        assertThat(notification.getEventId()).isNull();
        assertThat(notification.getDrawingId()).isNull();
        verify(ledgers).complete(common, 1L, 100L);
    }

    @Test void 대상이_없으면_알림을_저장하지_않는다() {
        when(ledgers.lock(false, 1L)).thenReturn(Optional.empty());
        service.create(false, 1L);
        verify(notifications, never()).saveAndFlush(any());
    }

    @Test void 양수_지급이_아니면_알림없이_제외한다() {
        when(ledgers.lock(true, 1L)).thenReturn(Optional.of(new Target(1, 2, 0, "ATTENDANCE", null)));
        service.create(true, 1L);
        verify(ledgers).exclude(true, 1L);
        verify(notifications, never()).saveAndFlush(any());
    }

    @Test void 숨긴_기존_알림도_재사용한다() {
        when(ledgers.lock(false, 1L)).thenReturn(Optional.of(new Target(1, 2, 1, "LIKE", "크리에이터")));
        Notification existing = Notification.forTicketReward(2L, false, 1L, "기존 본문");
        ReflectionTestUtils.setField(existing, "notificationId", 200L);
        existing.hide(java.time.Instant.now());
        when(notifications.findByTicketLedgerId(1L)).thenReturn(Optional.of(existing));
        service.create(false, 1L);
        verify(notifications, never()).saveAndFlush(any());
        verify(ledgers).complete(false, 1L, 200L);
    }

    @Test void 저장_실패를_삼키거나_완료로_처리하지_않는다() {
        when(ledgers.lock(false, 1L)).thenReturn(Optional.of(new Target(1, 2, 1, "LIKE", "크리에이터")));
        doThrow(new IllegalStateException("save failed")).when(notifications).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(false, 1L)).isInstanceOf(IllegalStateException.class);
        verify(ledgers, never()).complete(anyBoolean(), anyLong(), anyLong());
    }
}

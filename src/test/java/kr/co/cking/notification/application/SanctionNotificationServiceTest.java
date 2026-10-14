package kr.co.cking.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SanctionNotificationServiceTest {

    private static final Long MEMBER_ID = 20L;
    private static final Long SANCTION_ID = 900L;
    private static final Instant ENDS_AT = Instant.parse("2026-10-16T00:00:00Z");

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final SanctionNotificationService service = new SanctionNotificationService(notificationRepository);

    @Test
    void 경고로_시작된_제한은_경고_횟수와_해제_시각을_담아_제재_알림으로_저장한다() {
        service.createCommentRestrictionStartedByWarning(MEMBER_ID, SANCTION_ID, 2, ENDS_AT);

        Notification saved = savedNotification();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getSanctionId()).isEqualTo(SANCTION_ID);
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_STARTED);
        assertThat(saved.getTitle()).isEqualTo("댓글 작성 제한 안내");
        assertThat(saved.getBody()).contains("경고 2회").contains("2026-10-16 00:00 UTC까지");
        // 이벤트·추첨·당첨과 무관한 알림이다.
        assertThat(saved.getEventId()).isNull();
        assertThat(saved.getDrawingId()).isNull();
        assertThat(saved.getWinnerId()).isNull();
        assertThat(saved.getReadAt()).isNull();
    }

    @Test
    void 수동_제한은_경고_횟수_없이_해제_시각만_안내한다() {
        service.createCommentRestrictionStartedManually(MEMBER_ID, SANCTION_ID, ENDS_AT);

        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_STARTED);
        assertThat(saved.getBody()).contains("관리자가 댓글 작성을 제한했습니다").contains("2026-10-16 00:00 UTC까지");
        assertThat(saved.getBody()).doesNotContain("경고");
    }

    @Test
    void 해제_시각의_초_이하는_버리지_않고_다음_분으로_올려_안내한다() {
        service.createCommentRestrictionStartedManually(
                MEMBER_ID, SANCTION_ID, Instant.parse("2026-10-16T12:34:45.123456Z"));

        assertThat(savedNotification().getBody()).contains("2026-10-16 12:35 UTC까지");
    }

    @Test
    void 해제_시각이_정확히_분_경계이면_올리지_않는다() {
        service.createCommentRestrictionStartedManually(MEMBER_ID, SANCTION_ID, Instant.parse("2026-10-16T12:35:00Z"));

        assertThat(savedNotification().getBody()).contains("2026-10-16 12:35 UTC까지");
    }

    @Test
    void 해제_알림은_해제_종류로_저장한다() {
        service.createCommentRestrictionReleased(MEMBER_ID, SANCTION_ID);

        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_RELEASED);
        assertThat(saved.getTitle()).isEqualTo("댓글 작성 제한 해제 안내");
        assertThat(saved.getSanctionId()).isEqualTo(SANCTION_ID);
    }

    private Notification savedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }
}

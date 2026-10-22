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
    void 스페이스_차단_시작_알림은_크리에이터_이름과_해제_시각을_담고_사유는_담지_않는다() {
        service.createSpaceCommentBlockStarted(
                MEMBER_ID, SANCTION_ID, "홍길동", Instant.parse("2026-10-16T12:34:45Z"));

        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_STARTED);
        assertThat(saved.getTitle()).isEqualTo("스페이스 댓글 작성 제한 안내");
        assertThat(saved.getBody()).contains("홍길동의 스페이스").contains("2026-10-16 12:35 UTC까지");
        assertThat(saved.getBody()).doesNotContain("경고");
        assertThat(saved.getSanctionId()).isEqualTo(SANCTION_ID);
        assertThat(saved.getEventId()).isNull();
    }

    @Test
    void 스페이스_경고_알림은_크리에이터_이름과_경고_횟수와_그_스페이스의_해제_시각을_담는다() {
        service.createSpaceCommentRestrictionStartedByWarning(
                MEMBER_ID, SANCTION_ID, "홍길동", 2, Instant.parse("2026-10-16T12:34:45Z"));

        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_STARTED);
        assertThat(saved.getTitle()).isEqualTo("스페이스 댓글 작성 제한 안내");
        assertThat(saved.getBody()).contains("홍길동의 스페이스").contains("경고 2회")
                .contains("2026-10-16 12:35 UTC까지").contains("이 스페이스에서");
        assertThat(saved.getSanctionId()).isEqualTo(SANCTION_ID);
        assertThat(saved.getEventId()).isNull();
    }

    @Test
    void 스페이스_차단_해제_알림은_해제_종류로_크리에이터_이름을_담는다() {
        service.createSpaceCommentBlockReleased(MEMBER_ID, SANCTION_ID, "홍길동");

        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_RELEASED);
        assertThat(saved.getTitle()).isEqualTo("스페이스 댓글 작성 제한 해제 안내");
        // 다른 제한이 남아 있을 수 있으므로 해제된 사실만 안내하고 작성 가능 여부는 약속하지 않는다.
        assertThat(saved.getBody()).isEqualTo("홍길동의 스페이스에서 댓글 작성 제한이 해제되었습니다.");
    }

    @Test
    void 해제_알림은_해제_종류로_저장한다() {
        service.createCommentRestrictionReleased(MEMBER_ID, SANCTION_ID);

        Notification saved = savedNotification();
        assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT_RESTRICTION_RELEASED);
        assertThat(saved.getTitle()).isEqualTo("댓글 작성 제한 해제 안내");
        // 스페이스 차단이 남아 있을 수 있으므로 해제된 사실만 안내하고 작성 가능 여부는 약속하지 않는다.
        assertThat(saved.getBody()).isEqualTo("댓글 작성 제한이 해제되었습니다.");
        assertThat(saved.getSanctionId()).isEqualTo(SANCTION_ID);
    }

    private Notification savedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }
}

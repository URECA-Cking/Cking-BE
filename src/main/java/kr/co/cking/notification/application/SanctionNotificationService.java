package kr.co.cking.notification.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제재(댓글 작성 제한) 시작·해제를 작성자에게 알리는 인앱 Notification을 만든다(이슈 #572). 제재 도메인은 호출하는
 * Transaction 안에서 이 서비스를 부르므로, 알림은 제재 저장과 함께 커밋되거나 함께 롤백된다. 같은 제재의 같은 종류 알림은
 * {@code (sanction_id, type)} 유니크로 한 번만 생성된다.
 *
 * <p>알림에는 제재 사유(수동 제한의 관리자 메모)를 담지 않는다. 해제 시각은 서버 UTC 기준 분 단위로 표기하며, 실제 종료 시각의
 * 초 이하는 버리지 않고 다음 분으로 올려 안내 시각보다 일찍 풀린 것처럼 보이지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SanctionNotificationService {

    private static final DateTimeFormatter ENDS_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final String STARTED_TITLE = "댓글 작성 제한 안내";
    private static final String RELEASED_TITLE = "댓글 작성 제한 해제 안내";
    private static final String SPACE_STARTED_TITLE = "스페이스 댓글 작성 제한 안내";
    private static final String SPACE_RELEASED_TITLE = "스페이스 댓글 작성 제한 해제 안내";
    private static final String RELEASED_BODY = "댓글 작성 제한이 해제되었습니다.";

    private final NotificationRepository notificationRepository;

    /**
     * 관리자가 신고된 댓글을 숨겨 경고가 기록되고 댓글 작성 제한이 시작됐음을 알린다.
     *
     * @param warningCount 집계 기간 안의 누적 경고 횟수(이번 경고 포함)
     * @param endsAt       댓글 작성 제한이 모두 풀리는 시각. 이번 제재 하나가 아니라 지금 유효한 제한 중 가장 늦게 끝나는 시각이다
     */
    public void createCommentRestrictionStartedByWarning(
            Long memberId, Long sanctionId, long warningCount, Instant endsAt) {
        String body = "신고된 댓글이 관리자에 의해 숨김 처리되어 경고 " + warningCount + "회가 기록되었습니다. "
                + startedTail(endsAt);
        save(memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, STARTED_TITLE, body);
    }

    /** 관리자가 직접 댓글 작성을 제한했음을 알린다. {@code endsAt}은 제한이 모두 풀리는(가장 늦은) 시각이다. */
    public void createCommentRestrictionStartedManually(Long memberId, Long sanctionId, Instant endsAt) {
        String body = "관리자가 댓글 작성을 제한했습니다. " + startedTail(endsAt);
        save(memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, STARTED_TITLE, body);
    }

    /** 댓글 작성 제한이 모두 풀렸음을 알린다. 다른 제한이 남아 있으면 호출하지 않는다. */
    public void createCommentRestrictionReleased(Long memberId, Long sanctionId) {
        save(memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_RELEASED, RELEASED_TITLE, RELEASED_BODY);
    }

    /**
     * 크리에이터가 자기 스페이스에서 댓글 작성을 막았음을 알린다. 알림 종류는 플랫폼 제한과 같고 본문으로 구분한다. 차단 사유는
     * 담지 않고, {@code endsAt}은 이 차단이 풀리는 시각이다.
     */
    public void createSpaceCommentBlockStarted(Long memberId, Long sanctionId, String creatorName, Instant endsAt) {
        String body = creatorName + "의 스페이스에서 댓글 작성이 제한되었습니다. "
                + ENDS_AT_FORMAT.format(ceilToMinute(endsAt)) + "까지 이 스페이스에서 댓글을 작성하거나 수정할 수 없습니다.";
        save(memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_STARTED, SPACE_STARTED_TITLE, body);
    }

    /** 크리에이터가 스페이스 댓글 작성 차단을 풀었음을 알린다. */
    public void createSpaceCommentBlockReleased(Long memberId, Long sanctionId, String creatorName) {
        String body = creatorName + "의 스페이스에서 댓글 작성 제한이 해제되었습니다.";
        save(memberId, sanctionId, NotificationType.COMMENT_RESTRICTION_RELEASED, SPACE_RELEASED_TITLE, body);
    }

    private String startedTail(Instant endsAt) {
        return ENDS_AT_FORMAT.format(ceilToMinute(endsAt)) + "까지 댓글을 작성하거나 수정할 수 없습니다.";
    }

    private Instant ceilToMinute(Instant instant) {
        Instant truncated = instant.truncatedTo(ChronoUnit.MINUTES);
        return truncated.equals(instant) ? instant : truncated.plus(1, ChronoUnit.MINUTES);
    }

    private void save(Long memberId, Long sanctionId, NotificationType type, String title, String body) {
        notificationRepository.save(Notification.forSanction(memberId, sanctionId, type, title, body));
    }
}

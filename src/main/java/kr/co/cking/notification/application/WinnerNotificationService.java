package kr.co.cking.notification.application;

import java.util.List;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 결과 공개 후 Winner에게 전달할 인앱 Notification 생성을 담당한다. */
@Service
@RequiredArgsConstructor
public class WinnerNotificationService {

    private static final String INITIAL_WINNER_TITLE = "당첨 안내";
    private static final String INITIAL_WINNER_BODY = "이벤트 당첨자로 선정되었습니다.";
    private static final String REDRAW_WINNER_TITLE = "재추첨 당첨 안내";
    private static final String REDRAW_WINNER_BODY = "이벤트 재추첨 당첨자로 선정되었습니다.";

    private final NotificationRepository notificationRepository;

    /** INITIAL Drawing의 Winner마다 최초 당첨 Notification을 저장한다. */
    public void createInitialWinnerNotifications(
            Long eventId,
            Long drawingId,
            List<WinnerNotificationTarget> targets
    ) {
        saveWinnerNotifications(
                eventId, drawingId, targets,
                NotificationType.INITIAL_WINNER, INITIAL_WINNER_TITLE, INITIAL_WINNER_BODY
        );
    }

    /** REDRAW Drawing의 Winner마다 재추첨 당첨 Notification을 저장한다. */
    public void createRedrawWinnerNotifications(
            Long eventId,
            Long drawingId,
            List<WinnerNotificationTarget> targets
    ) {
        saveWinnerNotifications(
                eventId, drawingId, targets,
                NotificationType.REDRAW_WINNER, REDRAW_WINNER_TITLE, REDRAW_WINNER_BODY
        );
    }

    /** 지정한 알림 유형·문구로 대상 Winner별 Notification을 만들고 일괄 저장한다. */
    private void saveWinnerNotifications(
            Long eventId,
            Long drawingId,
            List<WinnerNotificationTarget> targets,
            NotificationType notificationType,
            String title,
            String body
    ) {
        List<Notification> notifications = targets.stream()
                .map(target -> new Notification(
                        target.memberId(), eventId, drawingId, target.winnerId(),
                        notificationType, title, body
                ))
                .toList();
        notificationRepository.saveAll(notifications);
    }
}

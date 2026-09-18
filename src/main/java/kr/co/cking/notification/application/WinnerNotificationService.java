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
        List<Notification> notifications = targets.stream()
                .map(target -> new Notification(
                        target.memberId(), eventId, drawingId, target.winnerId(),
                        NotificationType.INITIAL_WINNER, INITIAL_WINNER_TITLE, INITIAL_WINNER_BODY
                ))
                .toList();
        notificationRepository.saveAll(notifications);
    }

    /** REDRAW Drawing의 Winner마다 재추첨 당첨 Notification을 저장한다. */
    public void createRedrawWinnerNotifications(
            Long eventId,
            Long drawingId,
            List<WinnerNotificationTarget> targets
    ) {
        List<Notification> notifications = targets.stream()
                .map(target -> new Notification(
                        target.memberId(), eventId, drawingId, target.winnerId(),
                        NotificationType.REDRAW_WINNER, REDRAW_WINNER_TITLE, REDRAW_WINNER_BODY
                ))
                .toList();
        notificationRepository.saveAll(notifications);
    }
}

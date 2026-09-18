package kr.co.cking.drawing.application;

import java.util.List;
import kr.co.cking.drawing.application.DrawingPublicationResult.PublicationOutcome;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.notification.application.WinnerNotificationService;
import kr.co.cking.notification.application.WinnerNotificationTarget;
import kr.co.cking.winner.repository.WinnerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 외부 Drawing 공개 유스케이스의 진입점이다.
 *
 * <p>Drawing 공개와 후속 Notification 생성은 이 서비스의 Transaction 안에서 조합한다.
 * 현재 공개 상태 전이는 {@link DrawingPublicationService}에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class PublicationService {

    private final DrawingPublicationService drawingPublicationService;
    private final WinnerRepository winnerRepository;
    private final WinnerNotificationService winnerNotificationService;

    /**
     * INITIAL·REDRAW Drawing 공개, 필요한 Event 상태 검증·전이, 유형별 최초 당첨자 알림 생성을 하나의 트랜잭션으로 처리한다.
     * 이미 공개된 Drawing의 재요청은 알림을 추가 생성하지 않고 현재 상태만 반환한다.
     */
    @Transactional
    public DrawingPublicationResult publish(Long drawingId, Long adminId) {
        DrawingPublicationResult result = drawingPublicationService.publish(drawingId, adminId);
        if (result.outcome() == PublicationOutcome.PUBLISHED) {
            createWinnerNotifications(result);
        }
        return result;
    }

    /** 최초 공개된 Drawing의 유형에 맞게 당첨자 Notification 생성을 위임한다. */
    private void createWinnerNotifications(DrawingPublicationResult result) {
        List<WinnerNotificationTarget> targets = winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(result.drawingId())
                .stream()
                .map(winner -> new WinnerNotificationTarget(winner.getId(), winner.getMemberId()))
                .toList();
        if (result.drawingType() == DrawingType.INITIAL) {
            createInitialWinnerNotifications(result, targets);
            return;
        }
        createRedrawWinnerNotifications(result, targets);
    }

    /** INITIAL Drawing의 당첨자에게 최초 당첨 알림 생성을 위임한다. */
    private void createInitialWinnerNotifications(
            DrawingPublicationResult result,
            List<WinnerNotificationTarget> targets
    ) {
        winnerNotificationService.createInitialWinnerNotifications(result.eventId(), result.drawingId(), targets);
    }

    /** REDRAW Drawing의 당첨자에게 재추첨 당첨 알림 생성을 위임한다. */
    private void createRedrawWinnerNotifications(
            DrawingPublicationResult result,
            List<WinnerNotificationTarget> targets
    ) {
        winnerNotificationService.createRedrawWinnerNotifications(result.eventId(), result.drawingId(), targets);
    }
}

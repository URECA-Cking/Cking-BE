package kr.co.cking.notification.application;

import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketRewardNotificationService {
    private final RewardNotificationLedgerStore ledgers;
    private final NotificationRepository notifications;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void create(boolean common, long ledgerId) {
        Target target = ledgers.lock(common, ledgerId).orElse(null);
        if (target == null) return;
        if (target.amount() <= 0) {
            ledgers.exclude(common, ledgerId);
            return;
        }
        Notification notification = (common ? notifications.findByCommonTicketLedgerId(ledgerId)
                : notifications.findByTicketLedgerId(ledgerId)).orElse(null);
        if (notification == null) {
            notification = notifications.saveAndFlush(Notification.forTicketReward(target.memberId(), common, ledgerId, body(common, target)));
        }
        NotificationType expected = common ? NotificationType.COMMON_TICKET_REWARDED : NotificationType.CREATOR_TICKET_REWARDED;
        if (!notification.isOwnedBy(target.memberId()) || notification.getType() != expected) {
            throw new IllegalStateException("지급 원장과 기존 알림이 일치하지 않습니다.");
        }
        ledgers.complete(common, ledgerId, notification.getNotificationId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void retry(boolean common, long ledgerId) {
        ledgers.defer(common, ledgerId);
    }

    private static String body(boolean common, Target target) {
        String reason;
        if (common) {
            reason = switch (target.rewardType() == null ? "" : target.rewardType()) {
                case "ATTENDANCE" -> MissionType.displayNameOf("ATTENDANCE");
                case "SIGNUP" -> "웰컴 가입";
                case "FOLLOW" -> "웰컴 팔로우";
                case "QUIZ" -> "웰컴 퀴즈";
                case "BONUS" -> "웰컴 보너스";
                default -> null;
            };
        } else {
            reason = "AI_QUIZ".equals(target.rewardType()) ? "AI 퀴즈" : MissionType.displayNameOf(target.rewardType());
        }
        if (reason == null || (!common && (target.creatorName() == null || target.creatorName().isBlank()))) {
            throw new IllegalStateException("지급 보상 종류를 확인할 수 없습니다.");
        }
        return reason + " 보상으로 " + (common ? "공용" : target.creatorName() + " 크리에이터 전용")
                + " 응모권 " + target.amount() + "장이 지급됐어요.";
    }
}

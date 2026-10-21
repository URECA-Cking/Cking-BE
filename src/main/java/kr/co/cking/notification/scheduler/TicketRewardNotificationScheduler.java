package kr.co.cking.notification.scheduler;

import kr.co.cking.notification.application.TicketRewardNotificationService;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "cking.notification.ticket-reward.enabled", havingValue = "true")
public class TicketRewardNotificationScheduler {
    private final RewardNotificationLedgerStore ledgers;
    private final TicketRewardNotificationService notifications;
    private final int batchSize;

    public TicketRewardNotificationScheduler(RewardNotificationLedgerStore ledgers, TicketRewardNotificationService notifications,
            @Value("${cking.notification.ticket-reward.batch-size:20}") int batchSize) {
        this.ledgers = ledgers;
        this.notifications = notifications;
        this.batchSize = Math.clamp(batchSize, 1, 100);
    }

    @Scheduled(fixedDelayString = "${cking.notification.ticket-reward.interval-ms:30000}",
            initialDelayString = "${cking.notification.ticket-reward.interval-ms:30000}")
    public void deliver() {
        for (boolean common : new boolean[]{false, true}) {
            try {
                for (long id : ledgers.findDue(common, batchSize)) {
                    try {
                        notifications.create(common, id);
                    } catch (RuntimeException e) {
                        log.warn("응모권 지급 알림 생성 실패. common={}, ledgerId={}", common, id);
                        try { notifications.retry(common, id); }
                        catch (RuntimeException retryError) {
                            log.warn("응모권 지급 알림 재시도 예약 실패. common={}, ledgerId={}", common, id);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("응모권 지급 알림 대상 조회 실패. common={}", common);
            }
        }
    }
}

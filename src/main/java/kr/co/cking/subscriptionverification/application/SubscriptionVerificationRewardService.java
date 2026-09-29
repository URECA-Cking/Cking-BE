package kr.co.cking.subscriptionverification.application;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 승인된 구독 인증을 고정된 ONCE 요청으로 Ticket EARN에 전달한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionVerificationRewardService {

    private static final long REWARD_AMOUNT = 1L;

    private final TicketOnceEarnService ticketOnceEarnService;
    private final SubscriptionVerificationRewardCompletionService rewardCompletionService;
    private final Clock clock;

    /** 실패 시 APPROVED + PENDING을 유지해 같은 요청 정보로 Recovery가 재시도하게 한다. */
    public void reward(SubscriptionVerificationProcessingClaim claim) {
        EarnCommand command = commandOf(claim);
        EarnResult result;
        try {
            result = ticketOnceEarnService.earn(command);
        } catch (RuntimeException exception) {
            log.warn("구독 인증 ONCE 보상 요청에 실패해 Recovery에 맡깁니다. verificationId={}",
                    claim.verificationId(), exception);
            return;
        }

        if (result.code() != EarnResultCode.EARN_ACCEPTED
                && result.code() != EarnResultCode.ALREADY_PROCESSED) {
            log.warn("구독 인증 ONCE 보상이 수락되지 않아 Recovery에 맡깁니다. verificationId={}, result={}",
                    claim.verificationId(), result.code());
            return;
        }

        try {
            rewardCompletionService.accept(claim.verificationId(), clock.instant());
        } catch (RuntimeException exception) {
            log.warn("구독 인증 보상 완료 상태 저장에 실패해 멱등 재처리에 맡깁니다. verificationId={}",
                    claim.verificationId(), exception);
        }
    }

    private EarnCommand commandOf(SubscriptionVerificationProcessingClaim claim) {
        return new EarnCommand(
                UUID.fromString(claim.rewardRequestId()),
                claim.memberId(),
                claim.creatorId(),
                MissionType.YOUTUBE_SUBSCRIPTION.name(),
                claim.missionId(),
                claim.rewardPeriodKey(),
                missionKeyOf(claim.creatorId()),
                REWARD_AMOUNT,
                EarnRewardPolicy.ONCE
        );
    }

    private String missionKeyOf(Long creatorId) {
        return "%s:%d".formatted(
                MissionType.YOUTUBE_SUBSCRIPTION.name().toLowerCase(Locale.ROOT), creatorId);
    }
}

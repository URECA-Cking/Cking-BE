package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class SubscriptionVerificationRewardServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:10Z");

    private final TicketOnceEarnService ticketOnceEarnService = mock(TicketOnceEarnService.class);
    private final SubscriptionVerificationRewardCompletionService completionService =
            mock(SubscriptionVerificationRewardCompletionService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SubscriptionVerificationRewardService service =
            new SubscriptionVerificationRewardService(ticketOnceEarnService, completionService, clock);

    @ParameterizedTest
    @EnumSource(value = EarnResultCode.class, names = {"EARN_ACCEPTED", "ALREADY_PROCESSED"})
    void 수락되거나_이미_처리된_ONCE_보상은_ACCEPTED로_확정한다(EarnResultCode resultCode) {
        SubscriptionVerificationProcessingClaim claim = claim();
        given(ticketOnceEarnService.earn(any())).willReturn(new EarnResult(resultCode));

        SubscriptionVerificationRewardAttemptResult attemptResult = service.reward(claim);

        ArgumentCaptor<EarnCommand> commandCaptor = ArgumentCaptor.forClass(EarnCommand.class);
        then(ticketOnceEarnService).should().earn(commandCaptor.capture());
        EarnCommand command = commandCaptor.getValue();
        assertThat(command.requestId()).isEqualTo(UUID.fromString(claim.rewardRequestId()));
        assertThat(command.userId()).isEqualTo(claim.memberId());
        assertThat(command.creatorId()).isEqualTo(claim.creatorId());
        assertThat(command.missionId()).isEqualTo(claim.missionId());
        assertThat(command.missionType()).isEqualTo("YOUTUBE_SUBSCRIPTION");
        assertThat(command.missionKey()).isEqualTo("youtube_subscription:42");
        assertThat(command.periodKey()).isEqualTo(claim.rewardPeriodKey());
        assertThat(command.amount()).isEqualTo(1L);
        assertThat(command.rewardPolicy()).isEqualTo(EarnRewardPolicy.ONCE);
        assertThat(attemptResult).isEqualTo(SubscriptionVerificationRewardAttemptResult.ACCEPTED);
        then(completionService).should().accept(claim.verificationId(), NOW);
    }

    @Test
    void 수락되지_않은_보상은_PENDING으로_남기고_완료_저장을_호출하지_않는다() {
        given(ticketOnceEarnService.earn(any()))
                .willReturn(new EarnResult(EarnResultCode.EARN_PROCESSING_FAILED));

        SubscriptionVerificationRewardAttemptResult result = service.reward(claim());

        assertThat(result).isEqualTo(SubscriptionVerificationRewardAttemptResult.RETRY_REQUIRED);
        then(completionService).should(never()).accept(any(), any());
    }

    @Test
    void Ticket_ONCE_호출_예외는_전파하지_않고_Recovery에_맡긴다() {
        willThrow(new IllegalStateException("redis unavailable"))
                .given(ticketOnceEarnService).earn(any());

        assertThat(service.reward(claim()))
                .isEqualTo(SubscriptionVerificationRewardAttemptResult.RETRY_REQUIRED);

        then(completionService).should(never()).accept(any(), any());
    }

    @Test
    void 보상_성공_뒤_DB_확정_실패도_멱등_Recovery에_맡긴다() {
        given(ticketOnceEarnService.earn(any()))
                .willReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        willThrow(new IllegalStateException("database unavailable"))
                .given(completionService).accept(123L, NOW);

        assertThat(service.reward(claim()))
                .isEqualTo(SubscriptionVerificationRewardAttemptResult.RETRY_REQUIRED);
    }

    private static SubscriptionVerificationProcessingClaim claim() {
        return new SubscriptionVerificationProcessingClaim(
                123L,
                "10000000-0000-4000-8000-000000000001",
                7L,
                42L,
                103L,
                "subscription-verifications/123.jpg",
                "채널",
                "@channel",
                "20000000-0000-4000-8000-000000000001",
                "2026-09-28",
                1,
                Instant.parse("2026-09-29T00:01:30Z"));
    }
}

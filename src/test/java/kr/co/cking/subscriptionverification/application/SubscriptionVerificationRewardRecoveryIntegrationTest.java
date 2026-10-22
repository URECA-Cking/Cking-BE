package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Verification 확정 실패 뒤 실제 ONCE 보상 재시도가 한 번의 적립으로 수렴하는지 검증한다. */
@SpringBootTest(properties = "cking.scheduling.enabled=false")
class SubscriptionVerificationRewardRecoveryIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-29T00:00:00Z");
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(2);
    private static final long AWAIT_TIMEOUT_MILLIS = 8_000L;

    @Autowired private SubscriptionVerificationRewardService rewardService;
    @Autowired private SubscriptionVerificationRecoveryService recoveryService;
    @Autowired private SubscriptionVerificationProcessingClaimService claimService;
    @Autowired private SubscriptionVerificationProcessingCompletionService processingCompletionService;
    @Autowired private SubscriptionVerificationRepository verificationRepository;
    @Autowired private TicketOnceEarnService ticketOnceEarnService;
    @Autowired private UserTicketBalanceRepository balanceRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MissionRepository missionRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private SubscriptionVerificationRewardCompletionService rewardCompletionService;

    private Member owner;
    private Member participant;
    private Creator creator;
    private Mission mission;
    private SubscriptionVerification verification;
    private Set<String> redisKeys = Set.of();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(
                new Member("reward-owner-" + suffix, null, null, MemberRole.USER));
        participant = memberRepository.saveAndFlush(
                new Member("reward-user-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), "reward-creator-" + suffix));
        mission = missionRepository.saveAndFlush(new Mission(
                creator.getCreatorId(), MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null));
        verification = verificationRepository.saveAndFlush(SubscriptionVerification.pending(
                participant.getMemberId(),
                creator.getCreatorId(),
                mission.getMissionId(),
                UUID.randomUUID().toString(),
                "a".repeat(64),
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/" + UUID.randomUUID() + "/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                UUID.randomUUID().toString(),
                BASE_TIME));
        redisKeys = Set.of(
                TicketRedisKeys.idemMissionOnce(verification.getRewardRequestId()),
                TicketRedisKeys.earnGuard(
                        participant.getMemberId(),
                        MissionType.YOUTUBE_SUBSCRIPTION.name(),
                        creator.getCreatorId(),
                        "once"),
                TicketRedisKeys.balance(creator.getCreatorId(), participant.getMemberId()));
    }

    @AfterEach
    void cleanUp() {
        reset(rewardCompletionService);
        redisTemplate.delete(redisKeys);
        if (participant != null) {
            jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", participant.getMemberId());
            jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", participant.getMemberId());
            jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", participant.getMemberId());
            if (verification != null) {
                jdbcTemplate.update(
                        "DELETE FROM ticket_earn_request WHERE request_id = ?",
                        verification.getRewardRequestId());
                jdbcTemplate.update(
                        "DELETE FROM ticket_once_earn_request WHERE request_id = ?",
                        verification.getRewardRequestId());
                verificationRepository.deleteById(verification.getVerificationId());
            }
        }
        if (mission != null) {
            missionRepository.deleteById(mission.getMissionId());
        }
        if (creator != null) {
            creatorRepository.deleteById(creator.getCreatorId());
        }
        if (participant != null) {
            memberRepository.deleteById(participant.getMemberId());
        }
        if (owner != null) {
            memberRepository.deleteById(owner.getMemberId());
        }
    }

    @Test
    void Ticket_수락_뒤_Verification_확정이_실패해도_같은_요청으로_한번만_적립된다() {
        SubscriptionVerificationProcessingClaim claim = claimService.claim(
                verification.getVerificationId(), BASE_TIME, PROCESSING_LEASE).orElseThrow();
        assertThat(processingCompletionService.complete(
                verification.getVerificationId(),
                claim.processingToken(),
                SubscriptionVerificationProcessingOutcome.APPROVED,
                null,
                BASE_TIME.plusSeconds(1))).isTrue();
        SubscriptionVerificationRewardCommand rewardCommand = recoveryService
                .findRewardCandidates(BASE_TIME.plusSeconds(2), 1)
                .getFirst()
                .rewardCommand();

        doThrow(new IllegalStateException("Verification 보상 확정 강제 실패"))
                .when(rewardCompletionService)
                .accept(eq(verification.getVerificationId()), any(Instant.class));

        assertThat(rewardService.reward(rewardCommand))
                .isEqualTo(SubscriptionVerificationRewardAttemptResult.RETRY_REQUIRED);
        assertThat(redisBalance()).isEqualTo(1L);
        assertThat(ticketOnceEarnRequestStatus()).isEqualTo("ACCEPTED");
        assertThat(verificationRepository.findById(verification.getVerificationId()).orElseThrow()
                .getRewardStatus()).isEqualTo(VerificationRewardStatus.PENDING);

        assertThat(recoveryService.scheduleRewardRetry(
                verification.getVerificationId(),
                0,
                BASE_TIME.plusSeconds(2),
                BASE_TIME.plusSeconds(3))).isTrue();
        SubscriptionVerificationRewardCommand retryCommand = recoveryService
                .findRewardCandidates(BASE_TIME.plusSeconds(3), 1)
                .getFirst()
                .rewardCommand();
        assertThat(retryCommand).isEqualTo(rewardCommand);

        assertThat(ticketOnceEarnService.earn(toEarnCommand(retryCommand)).code())
                .isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(redisBalance()).isEqualTo(1L);

        reset(rewardCompletionService);

        assertThat(rewardService.reward(retryCommand))
                .isEqualTo(SubscriptionVerificationRewardAttemptResult.ACCEPTED);
        assertThat(redisBalance()).isEqualTo(1L);
        assertThat(awaitDatabaseBalance()).isEqualTo(1L);
        assertThat(ticketLedgerCount()).isEqualTo(1);

        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.APPROVED);
        assertThat(persisted.getRewardStatus()).isEqualTo(VerificationRewardStatus.ACCEPTED);
        assertThat(persisted.getRewardRequestId()).isEqualTo(rewardCommand.rewardRequestId());
        assertThat(persisted.getRewardPeriodKey()).isEqualTo(rewardCommand.rewardPeriodKey());
    }

    private EarnCommand toEarnCommand(SubscriptionVerificationRewardCommand command) {
        return new EarnCommand(
                UUID.fromString(command.rewardRequestId()),
                command.memberId(),
                command.creatorId(),
                MissionType.YOUTUBE_SUBSCRIPTION.name(),
                command.missionId(),
                command.rewardPeriodKey(),
                "youtube_subscription:" + command.creatorId(),
                1L,
                EarnRewardPolicy.ONCE);
    }

    private long redisBalance() {
        String value = redisTemplate.opsForValue().get(
                TicketRedisKeys.balance(creator.getCreatorId(), participant.getMemberId()));
        return value == null ? 0L : Long.parseLong(value);
    }

    private long awaitDatabaseBalance() {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            long balance = balanceRepository
                    .findByMemberIdAndCreatorId(participant.getMemberId(), creator.getCreatorId())
                    .map(UserTicketBalance::getBalance)
                    .orElse(0L);
            if (balance == 1L) {
                return balance;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("구독 인증 ONCE 보상이 제한 시간 안에 DB Balance로 반영되지 않았습니다.");
    }

    private int ticketLedgerCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ticket_ledger
                WHERE member_id = ? AND creator_id = ? AND type = 'EARN'
                """, Integer.class, participant.getMemberId(), creator.getCreatorId());
    }

    private String ticketOnceEarnRequestStatus() {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM ticket_once_earn_request
                WHERE request_id = ?
                """, String.class, verification.getRewardRequestId());
    }
}

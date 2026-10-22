package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "cking.scheduling.enabled=false")
class SubscriptionVerificationRecoveryServiceIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-29T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired private SubscriptionVerificationRecoveryService recoveryService;
    @Autowired private SubscriptionVerificationProcessingClaimService claimService;
    @Autowired private SubscriptionVerificationProcessingCompletionService completionService;
    @Autowired private SubscriptionVerificationRepository verificationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MissionRepository missionRepository;

    private Member owner;
    private Member participant;
    private Creator creator;
    private Mission mission;
    private SubscriptionVerification verification;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(
                new Member("recovery-owner-" + suffix, null, null, MemberRole.USER));
        participant = memberRepository.saveAndFlush(
                new Member("recovery-user-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), "recovery-creator-" + suffix));
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
    }

    @AfterEach
    void cleanUp() {
        if (verification != null) {
            verificationRepository.deleteById(verification.getVerificationId());
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
    void 유예_시간이_지난_PENDING만_복구_대상으로_조회한다() {
        assertThat(recoveryService.findProcessingCandidates(
                BASE_TIME.plusSeconds(30), BASE_TIME.minusSeconds(30), 3, 20)).isEmpty();

        assertThat(recoveryService.findProcessingCandidates(
                BASE_TIME.plusSeconds(61), BASE_TIME.plusSeconds(1), 3, 20))
                .containsExactly(verification.getVerificationId());
    }

    @Test
    void 만료된_PROCESSING은_상한_전까지만_재선점_대상이다() {
        SubscriptionVerificationProcessingClaim first = claim(BASE_TIME);

        assertThat(recoveryService.findProcessingCandidates(
                first.processingLeaseUntil().minusMillis(1), BASE_TIME, 3, 20)).isEmpty();
        assertThat(recoveryService.findProcessingCandidates(
                first.processingLeaseUntil(), BASE_TIME, 3, 20))
                .containsExactly(verification.getVerificationId());
    }

    @Test
    void 처리_시도_상한을_소진하면_FAILED로_조건부_종료한다() {
        SubscriptionVerificationProcessingClaim first = claim(BASE_TIME);
        SubscriptionVerificationProcessingClaim second = claim(
                first.processingLeaseUntil());
        SubscriptionVerificationProcessingClaim third = claim(
                second.processingLeaseUntil());
        Instant failedAt = third.processingLeaseUntil();

        assertThat(claimService.claim(
                verification.getVerificationId(), failedAt, LEASE)).isEmpty();
        assertThat(recoveryService.findExhaustedProcessingIds(failedAt, 3, 20))
                .containsExactly(verification.getVerificationId());
        assertThat(recoveryService.failExhaustedProcessing(
                verification.getVerificationId(), failedAt, 3)).isTrue();
        assertThat(recoveryService.failExhaustedProcessing(
                verification.getVerificationId(), failedAt, 3)).isFalse();

        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.FAILED);
        assertThat(persisted.getReasonCode()).isEqualTo("PROCESSING_ATTEMPTS_EXHAUSTED");
        assertThat(persisted.getActiveGuard()).isNull();
        assertThat(persisted.getProcessedAt()).isEqualTo(failedAt);
    }

    @Test
    void 승인_보상_실패를_backoff로_예약하고_동결_입력으로_다시_조회한다() {
        SubscriptionVerificationProcessingClaim claim = claim(BASE_TIME);
        completionService.complete(
                verification.getVerificationId(),
                claim.processingToken(),
                SubscriptionVerificationProcessingOutcome.APPROVED,
                null,
                BASE_TIME.plusSeconds(1));
        SubscriptionVerificationRecoveryTarget target = recoveryService
                .findRewardCandidates(BASE_TIME.plusSeconds(2), 20)
                .getFirst();

        assertThat(target.rewardCommand().rewardRequestId())
                .isEqualTo(verification.getRewardRequestId());
        assertThat(target.rewardCommand().rewardPeriodKey()).isEqualTo("2026-09-29");
        assertThat(recoveryService.scheduleRewardRetry(
                verification.getVerificationId(),
                0,
                BASE_TIME.plusSeconds(2),
                BASE_TIME.plusSeconds(62))).isTrue();
        assertThat(recoveryService.scheduleRewardRetry(
                verification.getVerificationId(),
                0,
                BASE_TIME.plusSeconds(2),
                BASE_TIME.plusSeconds(62))).isFalse();
        assertThat(recoveryService.findRewardCandidates(BASE_TIME.plusSeconds(61), 20)).isEmpty();

        SubscriptionVerificationRecoveryTarget retried = recoveryService
                .findRewardCandidates(BASE_TIME.plusSeconds(62), 20)
                .getFirst();
        assertThat(retried.rewardAttemptCount()).isEqualTo(1);
        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getRewardStatus()).isEqualTo(VerificationRewardStatus.RETRY_REQUIRED);
        assertThat(persisted.getNextAttemptAt()).isEqualTo(BASE_TIME.plusSeconds(62));
    }

    private SubscriptionVerificationProcessingClaim claim(Instant startedAt) {
        return claimService.claim(
                verification.getVerificationId(), startedAt, LEASE).orElseThrow();
    }
}

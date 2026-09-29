package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class SubscriptionVerificationProcessingClaimIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-29T00:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    @Autowired private SubscriptionVerificationProcessingClaimService claimService;
    @Autowired private SubscriptionVerificationProcessingCompletionService completionService;
    @Autowired private SubscriptionVerificationRepository verificationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MissionRepository missionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private Member owner;
    private Member participant;
    private Creator creator;
    private Mission mission;
    private SubscriptionVerification verification;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(new Member("claim-owner-" + suffix, null, null, MemberRole.USER));
        participant = memberRepository.saveAndFlush(
                new Member("claim-participant-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "claim-creator-" + suffix));
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
                BASE_TIME
        ));
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
    void 동시에_선점해도_한_Worker만_처리_소유권을_얻는다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<SubscriptionVerificationProcessingClaim>> first =
                    executor.submit(() -> claimAfter(start, BASE_TIME));
            Future<Optional<SubscriptionVerificationProcessingClaim>> second =
                    executor.submit(() -> claimAfter(start, BASE_TIME));
            start.countDown();

            List<Optional<SubscriptionVerificationProcessingClaim>> results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            SubscriptionVerification persisted = verificationRepository
                    .findById(verification.getVerificationId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.PROCESSING);
            assertThat(persisted.getAttemptCount()).isEqualTo(1);
            assertThat(persisted.getProcessingToken()).isNotBlank();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 만료되지_않은_lease는_재선점할_수_없다() {
        SubscriptionVerificationProcessingClaim first = claim(BASE_TIME);

        Optional<SubscriptionVerificationProcessingClaim> second = claimService.claim(
                verification.getVerificationId(), BASE_TIME.plusSeconds(30), LEASE_DURATION);

        assertThat(second).isEmpty();
        assertThat(verificationRepository.findById(verification.getVerificationId()).orElseThrow()
                .getProcessingToken()).isEqualTo(first.processingToken());
    }

    @Test
    void 만료된_lease는_새_token으로_재선점한다() {
        SubscriptionVerificationProcessingClaim first = claim(BASE_TIME);

        SubscriptionVerificationProcessingClaim reclaimed = claimService.claim(
                verification.getVerificationId(),
                first.processingLeaseUntil(),
                LEASE_DURATION
        ).orElseThrow();

        assertThat(reclaimed.processingToken()).isNotEqualTo(first.processingToken());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.processingLeaseUntil())
                .isEqualTo(first.processingLeaseUntil().plus(LEASE_DURATION));
    }

    @Test
    void 재선점_뒤_이전_token의_늦은_결과는_차단한다() {
        SubscriptionVerificationProcessingClaim first = claim(BASE_TIME);
        SubscriptionVerificationProcessingClaim reclaimed = claimService.claim(
                verification.getVerificationId(), first.processingLeaseUntil(), LEASE_DURATION).orElseThrow();

        boolean staleCompleted = completionService.complete(
                verification.getVerificationId(),
                first.processingToken(),
                SubscriptionVerificationProcessingOutcome.REJECTED,
                "CHANNEL_MISMATCH",
                first.processingLeaseUntil().plusSeconds(1));
        boolean ownerCompleted = completionService.complete(
                verification.getVerificationId(),
                reclaimed.processingToken(),
                SubscriptionVerificationProcessingOutcome.REJECTED,
                "CHANNEL_MISMATCH",
                first.processingLeaseUntil().plusSeconds(2));

        assertThat(staleCompleted).isFalse();
        assertThat(ownerCompleted).isTrue();
        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.REJECTED);
        assertThat(persisted.getReasonCode()).isEqualTo("CHANNEL_MISMATCH");
    }

    @Test
    void 현재_token의_APPROVED_결과만_보상_대기_상태로_저장한다() {
        SubscriptionVerificationProcessingClaim claimed = claim(BASE_TIME);

        boolean completed = completionService.complete(
                verification.getVerificationId(),
                claimed.processingToken(),
                SubscriptionVerificationProcessingOutcome.APPROVED,
                null,
                BASE_TIME.plusSeconds(1));

        assertThat(completed).isTrue();
        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.APPROVED);
        assertThat(persisted.getApprovedGuard()).isEqualTo((byte) 1);
        assertThat(persisted.getRewardStatus().name()).isEqualTo("PENDING");
        assertThat(persisted.getProcessingLeaseUntil()).isNull();
    }

    @Test
    void 처리_시작보다_이른_결과는_현재_token이어도_저장하지_않는다() {
        SubscriptionVerificationProcessingClaim claimed = claim(BASE_TIME);

        boolean completed = completionService.complete(
                verification.getVerificationId(),
                claimed.processingToken(),
                SubscriptionVerificationProcessingOutcome.FAILED,
                "PROVIDER_FAILURE",
                BASE_TIME.minusMillis(1));

        assertThat(completed).isFalse();
        assertThat(verificationRepository.findById(verification.getVerificationId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionVerificationStatus.PROCESSING);
    }

    @Test
    void lease가_만료되면_재선점_전이어도_기존_token의_결과를_저장하지_않는다() {
        SubscriptionVerificationProcessingClaim claimed = claim(BASE_TIME);

        boolean completed = completionService.complete(
                verification.getVerificationId(),
                claimed.processingToken(),
                SubscriptionVerificationProcessingOutcome.FAILED,
                "PROVIDER_FAILURE",
                claimed.processingLeaseUntil());

        assertThat(completed).isFalse();
        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.PROCESSING);
        assertThat(persisted.getProcessingToken()).isEqualTo(claimed.processingToken());
        assertThat(persisted.getProcessingLeaseUntil()).isEqualTo(claimed.processingLeaseUntil());
    }

    @Test
    void 선점은_호출자_Transaction이_rollback되어도_독립적으로_commit된다() {
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        outerTransaction.executeWithoutResult(status -> {
            assertThat(claimService.claim(
                    verification.getVerificationId(), BASE_TIME, LEASE_DURATION)).isPresent();
            status.setRollbackOnly();
        });

        SubscriptionVerification persisted = verificationRepository
                .findById(verification.getVerificationId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SubscriptionVerificationStatus.PROCESSING);
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
    }

    private Optional<SubscriptionVerificationProcessingClaim> claimAfter(
            CountDownLatch start,
            Instant startedAt
    ) throws InterruptedException {
        start.await();
        return claimService.claim(verification.getVerificationId(), startedAt, LEASE_DURATION);
    }

    private SubscriptionVerificationProcessingClaim claim(Instant startedAt) {
        return claimService.claim(verification.getVerificationId(), startedAt, LEASE_DURATION).orElseThrow();
    }
}

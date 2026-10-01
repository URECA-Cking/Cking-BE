package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisException;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisFailureType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisRequest;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import kr.co.cking.subscriptionverification.application.vision.VisionPlatform;
import kr.co.cking.subscriptionverification.application.vision.VisionSubscriptionState;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageHashLockRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageReuseRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import kr.co.cking.subscriptionverification.scheduler.SubscriptionVerificationRecoveryScheduler;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 채널 설정부터 VLM 판정과 Ticket Stream DB 반영까지 구독 인증의 최종 사용자 흐름을 검증한다. */
@SpringBootTest(properties = {
        "cking.scheduling.enabled=false",
        "cking.verification.youtube-subscription.submission-enabled=true",
        "cking.verification.youtube-subscription.gemini.api-key=test-gemini-key",
        "cking.verification.youtube-subscription.processing-executor.core-pool-size=1",
        "cking.verification.youtube-subscription.processing-executor.max-pool-size=1",
        "cking.verification.youtube-subscription.processing-executor.queue-capacity=4",
        "cking.verification.youtube-subscription.processing-executor.provider-max-concurrent-calls=1",
        "cking.verification.youtube-subscription.processing-executor.processing-lease-duration=PT90S",
        "cking.verification.youtube-subscription.recovery.batch-size=5"
})
@Import(YoutubeSubscriptionVerificationE2EIntegrationTest.E2ETestConfig.class)
class YoutubeSubscriptionVerificationE2EIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-30T00:00:00Z");
    private static final long AWAIT_TIMEOUT_MILLIS = 10_000L;

    @Autowired private CreatorYoutubeChannelService channelService;
    @Autowired private SubscriptionVerificationSubmissionService submissionService;
    @Autowired private SubscriptionVerificationQueryService queryService;
    @Autowired private SubscriptionVerificationRecoveryScheduler recoveryScheduler;
    @Autowired private SubscriptionVerificationRepository verificationRepository;
    @Autowired private SubscriptionVerificationImageReuseRepository imageReuseRepository;
    @Autowired private SubscriptionVerificationImageHashLockRepository hashLockRepository;
    @Autowired private CreatorYoutubeChannelRepository channelRepository;
    @Autowired private MissionRepository missionRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private UserTicketBalanceRepository balanceRepository;
    @Autowired private ObjectStorage objectStorage;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private ControllableVisionAnalysisPort visionAnalysisPort;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorIds = new ArrayList<>();
    private final List<Long> missionIds = new ArrayList<>();
    private final List<Long> verificationIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final Set<String> imageHashes = new java.util.HashSet<>();
    private final Set<String> redisKeys = new java.util.HashSet<>();

    @BeforeEach
    void setUp() {
        clock.set(BASE_TIME);
        visionAnalysisPort.reset();
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(redisKeys);
        for (Long verificationId : verificationIds) {
            imageReuseRepository.deleteById(verificationId);
        }
        imageReuseRepository.flush();
        for (Long memberId : memberIds) {
            jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM ticket_earn_request WHERE request_id IN "
                    + "(SELECT request_id FROM ticket_once_earn_request WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM ticket_once_earn_request WHERE member_id = ?", memberId);
        }
        if (!verificationIds.isEmpty()) {
            verificationRepository.deleteAllById(verificationIds);
            verificationRepository.flush();
        }
        objectKeys.forEach(this::deleteObjectQuietly);
        hashLockRepository.deleteAllById(imageHashes);
        hashLockRepository.flush();
        creatorIds.forEach(channelRepository::deleteById);
        missionIds.forEach(missionRepository::deleteById);
        creatorIds.forEach(creatorRepository::deleteById);
        memberIds.forEach(memberRepository::deleteById);
    }

    @Test
    void 채널_설정부터_승인_보상_조회까지_한번의_ONCE_적립으로_완료된다() throws Exception {
        CreatorFixture fixture = createCreatorFixture("e2e-approved");
        Member firstParticipant = createMember("e2e-first-user");
        Member secondParticipant = createMember("e2e-second-user");
        byte[] image = validImage(new Color(32, 96, 160));
        UUID firstRequestId = UUID.randomUUID();

        SubscriptionVerificationSubmissionResult first = submit(
                firstParticipant, fixture, firstRequestId, image);
        SubscriptionVerification firstCompleted = awaitRewardAccepted(first.verification().getVerificationId());

        assertThat(first.created()).isTrue();
        assertThat(firstCompleted.getStatus()).isEqualTo(SubscriptionVerificationStatus.APPROVED);
        assertThat(firstCompleted.getRewardStatus()).isEqualTo(VerificationRewardStatus.ACCEPTED);
        assertThat(firstCompleted.getAttemptCount()).isEqualTo(1);
        assertThat(queryService.getMine(firstParticipant.getMemberId(), firstCompleted.getVerificationId()))
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(SubscriptionVerificationPublicStatus.VERIFIED);
                    assertThat(result.rewarded()).isTrue();
                });
        assertThat(objectStorage.get(firstCompleted.getImageObjectKey()))
                .startsWith((byte) 0xFF, (byte) 0xD8)
                .endsWith((byte) 0xFF, (byte) 0xD9);
        assertThat(firstCompleted.getImageSha256()).hasSize(64);
        assertThat(visionAnalysisPort.commitBoundaryObserved()).isTrue();
        assertTicketPersistedOnce(firstParticipant, fixture, firstCompleted);
        assertThat(imageReuseRepository.findById(firstCompleted.getVerificationId()).orElseThrow().getReuseType())
                .isEqualTo(SubscriptionVerificationImageReuseType.FIRST_USE);

        SubscriptionVerificationSubmissionResult replay = submit(
                firstParticipant, fixture, firstRequestId, image);
        assertThat(replay.created()).isFalse();
        assertThat(replay.verification().getVerificationId()).isEqualTo(firstCompleted.getVerificationId());
        assertThat(verificationRepository.findAllByMemberIdAndCreatorIdAndMissionId(
                firstParticipant.getMemberId(), fixture.creatorId(), fixture.missionId())).hasSize(1);
        assertThat(currentBalance(firstParticipant.getMemberId(), fixture.creatorId())).isEqualTo(1L);

        assertThatThrownBy(() -> submit(
                firstParticipant, fixture, UUID.randomUUID(), image))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(SubscriptionVerificationErrorCode.VERIFICATION_ALREADY_APPROVED));
        assertThat(verificationRepository.findAllByMemberIdAndCreatorIdAndMissionId(
                firstParticipant.getMemberId(), fixture.creatorId(), fixture.missionId())).hasSize(1);
        assertTicketPersistedOnce(firstParticipant, fixture, firstCompleted);

        SubscriptionVerificationSubmissionResult reused = submit(
                secondParticipant, fixture, UUID.randomUUID(), image);
        SubscriptionVerification reusedCompleted = awaitRewardAccepted(reused.verification().getVerificationId());
        assertThat(imageReuseRepository.findById(reusedCompleted.getVerificationId()).orElseThrow())
                .satisfies(reuse -> {
                    assertThat(reuse.getReuseType())
                            .isEqualTo(SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER);
                    assertThat(reuse.getMatchedVerificationId()).isEqualTo(firstCompleted.getVerificationId());
                });
        assertTicketPersistedOnce(secondParticipant, fixture, reusedCompleted);
    }

    @Test
    void Provider_일시_실패는_lease_만료_후_Recovery가_재선점해_승인과_보상으로_수렴한다()
            throws Exception {
        CreatorFixture fixture = createCreatorFixture("e2e-recovery");
        Member participant = createMember("e2e-recovery-user");
        visionAnalysisPort.failRetryably(1);

        SubscriptionVerificationSubmissionResult submitted = submit(
                participant, fixture, UUID.randomUUID(), validImage(new Color(120, 80, 40)));
        SubscriptionVerification firstAttempt = awaitStatus(
                submitted.verification().getVerificationId(), SubscriptionVerificationStatus.PROCESSING);
        awaitVisionCalls(1);
        assertThat(firstAttempt.getAttemptCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        recoveryScheduler.recover();

        SubscriptionVerification completed = awaitRewardAccepted(firstAttempt.getVerificationId());
        assertThat(completed.getAttemptCount()).isEqualTo(2);
        assertThat(visionAnalysisPort.callCount()).isEqualTo(2);
        assertTicketPersistedOnce(participant, fixture, completed);
    }

    @Test
    void 명백한_미구독_판정은_REJECTED로_종료하고_Ticket을_지급하지_않는다() throws Exception {
        CreatorFixture fixture = createCreatorFixture("e2e-rejected");
        Member participant = createMember("e2e-rejected-user");
        visionAnalysisPort.rejectAsNotSubscribed();

        SubscriptionVerificationSubmissionResult submitted = submit(
                participant, fixture, UUID.randomUUID(), validImage(new Color(96, 48, 144)));
        SubscriptionVerification rejected = awaitStatus(
                submitted.verification().getVerificationId(), SubscriptionVerificationStatus.REJECTED);

        assertThat(rejected.getReasonCode()).isEqualTo("NOT_SUBSCRIBED");
        assertThat(rejected.getRewardStatus()).isEqualTo(VerificationRewardStatus.NOT_REQUESTED);
        assertThat(queryService.getLatestMine(
                participant.getMemberId(), fixture.creatorId(), fixture.missionId()))
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(SubscriptionVerificationPublicStatus.REJECTED);
                    assertThat(result.rewarded()).isFalse();
                });
        assertThat(currentBalance(participant.getMemberId(), fixture.creatorId())).isZero();
        assertThat(ticketLedgerCount(participant.getMemberId(), fixture.creatorId())).isZero();
        assertThat(missionCompletionCount(participant.getMemberId(), fixture)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ticket_once_earn_request WHERE request_id = ?
                """, Integer.class, rejected.getRewardRequestId())).isZero();
    }

    private CreatorFixture createCreatorFixture(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Member owner = createMember(prefix + "-owner-" + suffix);
        Creator creator = creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), prefix + "-creator-" + suffix));
        creatorIds.add(creator.getCreatorId());

        CreatorYoutubeChannelUpsertResult channel = channelService.put(
                owner.getMemberId(),
                new CreatorYoutubeChannelCommand("예상치 못한 필름", "@UnexpectedFilm_" + suffix));
        Mission mission = missionRepository.findByCreatorIdAndType(
                creator.getCreatorId(), MissionType.YOUTUBE_SUBSCRIPTION).orElseThrow();
        missionIds.add(mission.getMissionId());

        assertThat(channel.created()).isTrue();
        assertThat(channel.channel().getChannelHandle()).isEqualTo("@unexpectedfilm_" + suffix);
        assertThat(mission.getRewardAmount()).isEqualTo(1);
        return new CreatorFixture(creator.getCreatorId(), mission.getMissionId());
    }

    private Member createMember(String name) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
        memberIds.add(member.getMemberId());
        return member;
    }

    private SubscriptionVerificationSubmissionResult submit(
            Member participant,
            CreatorFixture fixture,
            UUID requestId,
            byte[] image
    ) {
        SubscriptionVerificationSubmissionResult result = submissionService.submit(
                new SubscriptionVerificationSubmissionCommand(
                        participant.getMemberId(),
                        fixture.creatorId(),
                        fixture.missionId(),
                        requestId,
                        image));
        if (result.created()) {
            track(result.verification());
        }
        return result;
    }

    private void track(SubscriptionVerification verification) {
        verificationIds.add(verification.getVerificationId());
        objectKeys.add(verification.getImageObjectKey());
        imageHashes.add(verification.getImageSha256());
        redisKeys.add(TicketRedisKeys.idemMissionOnce(verification.getRewardRequestId()));
        redisKeys.add(TicketRedisKeys.earnGuard(
                verification.getMemberId(),
                MissionType.YOUTUBE_SUBSCRIPTION.name(),
                verification.getCreatorId(),
                "once"));
        redisKeys.add(TicketRedisKeys.balance(
                verification.getCreatorId(), verification.getMemberId()));
    }

    private SubscriptionVerification awaitRewardAccepted(Long verificationId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            SubscriptionVerification verification = verificationRepository.findById(verificationId).orElseThrow();
            if (verification.getStatus() == SubscriptionVerificationStatus.APPROVED
                    && verification.getRewardStatus() == VerificationRewardStatus.ACCEPTED
                    && currentBalance(verification.getMemberId(), verification.getCreatorId()) == 1L
                    && ticketLedgerCount(verification.getMemberId(), verification.getCreatorId()) == 1) {
                return verification;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("구독 인증 승인·보상이 제한 시간 안에 완료되지 않았습니다. verificationId="
                + verificationId);
    }

    private SubscriptionVerification awaitStatus(
            Long verificationId,
            SubscriptionVerificationStatus expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            SubscriptionVerification verification = verificationRepository.findById(verificationId).orElseThrow();
            if (verification.getStatus() == expected) {
                return verification;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("구독 인증 상태가 제한 시간 안에 전이되지 않았습니다. expected=" + expected);
    }

    private void awaitVisionCalls(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (visionAnalysisPort.callCount() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Vision 분석 호출이 제한 시간 안에 완료되지 않았습니다. expected=" + expected);
    }

    private void assertTicketPersistedOnce(
            Member participant,
            CreatorFixture fixture,
            SubscriptionVerification verification
    ) {
        assertThat(currentBalance(participant.getMemberId(), fixture.creatorId())).isEqualTo(1L);
        assertThat(ticketLedgerCount(participant.getMemberId(), fixture.creatorId())).isEqualTo(1);
        assertThat(missionCompletionCount(participant.getMemberId(), fixture)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM ticket_once_earn_request WHERE request_id = ?
                """, String.class, verification.getRewardRequestId())).isEqualTo("ACCEPTED");
    }

    private long currentBalance(Long memberId, Long creatorId) {
        return balanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                .map(UserTicketBalance::getBalance)
                .orElse(0L);
    }

    private int ticketLedgerCount(Long memberId, Long creatorId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ticket_ledger
                WHERE member_id = ? AND creator_id = ? AND type = 'EARN'
                """, Integer.class, memberId, creatorId);
    }

    private int missionCompletionCount(Long memberId, CreatorFixture fixture) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mission_completion
                WHERE member_id = ? AND creator_id = ? AND mission_id = ?
                """, Integer.class, memberId, fixture.creatorId(), fixture.missionId());
    }

    private byte[] validImage(Color color) throws Exception {
        BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private void deleteObjectQuietly(String objectKey) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // 테스트 정리에서는 이미 삭제된 Object를 허용한다.
        }
    }

    private record CreatorFixture(Long creatorId, Long missionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class E2ETestConfig {

        @Bean
        @Primary
        MutableClock subscriptionVerificationE2EClock() {
            return new MutableClock(BASE_TIME);
        }

        @Bean
        @Primary
        ControllableVisionAnalysisPort subscriptionVerificationE2EVisionAnalysisPort(
                JdbcTemplate jdbcTemplate
        ) {
            return new ControllableVisionAnalysisPort(jdbcTemplate);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void advance(Duration duration) {
            current.updateAndGet(now -> now.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("E2E Clock은 UTC만 지원합니다.");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    static final class ControllableVisionAnalysisPort implements VisionAnalysisPort {

        private final AtomicInteger remainingRetryableFailures = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<VisionSubscriptionState> subscriptionState =
                new AtomicReference<>(VisionSubscriptionState.SUBSCRIBED);
        private final AtomicBoolean commitBoundaryObserved = new AtomicBoolean();
        private final JdbcTemplate jdbcTemplate;

        private ControllableVisionAnalysisPort(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void reset() {
            remainingRetryableFailures.set(0);
            calls.set(0);
            subscriptionState.set(VisionSubscriptionState.SUBSCRIBED);
            commitBoundaryObserved.set(false);
        }

        void failRetryably(int count) {
            remainingRetryableFailures.set(count);
        }

        void rejectAsNotSubscribed() {
            subscriptionState.set(VisionSubscriptionState.NOT_SUBSCRIBED);
        }

        int callCount() {
            return calls.get();
        }

        boolean commitBoundaryObserved() {
            return commitBoundaryObserved.get();
        }

        @Override
        public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
            assertCommittedProcessingVisible(request.targetChannelHandle());
            calls.incrementAndGet();
            if (remainingRetryableFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new VisionAnalysisException(
                        VisionAnalysisFailureType.RETRYABLE, "E2E 강제 일시 오류");
            }
            return new VisionAnalysisResult(
                    VisionPlatform.YOUTUBE,
                    request.targetChannelName(),
                    request.targetChannelHandle(),
                    subscriptionState.get(),
                    true,
                    0.99);
        }

        private void assertCommittedProcessingVisible(String targetChannelHandle) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new AssertionError("Vision 분석은 제출 Transaction 밖에서 실행되어야 합니다.");
            }
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM subscription_verification
                    WHERE target_channel_handle = ? AND status = 'PROCESSING'
                    """, Integer.class, targetChannelHandle);
            if (count == null || count != 1) {
                throw new AssertionError("Commit된 PROCESSING Verification을 조회할 수 없습니다.");
            }
            commitBoundaryObserved.set(true);
        }
    }
}

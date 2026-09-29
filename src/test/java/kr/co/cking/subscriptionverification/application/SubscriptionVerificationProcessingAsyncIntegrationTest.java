package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import kr.co.cking.subscriptionverification.application.vision.VisionPlatform;
import kr.co.cking.subscriptionverification.application.vision.VisionSubscriptionState;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실제 Spring @Async proxy와 bounded executor의 제출 경계를 검증한다. */
@SpringBootTest(properties = {
        "cking.verification.youtube-subscription.processing-executor.core-pool-size=1",
        "cking.verification.youtube-subscription.processing-executor.max-pool-size=1",
        "cking.verification.youtube-subscription.processing-executor.queue-capacity=1",
        "cking.verification.youtube-subscription.processing-executor.provider-max-concurrent-calls=1"
})
class SubscriptionVerificationProcessingAsyncIntegrationTest {

    @Autowired private SubscriptionVerificationProcessingTrigger trigger;
    @Autowired private SubscriptionVerificationRepository verificationRepository;
    @Autowired private ObjectStorage objectStorage;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MissionRepository missionRepository;

    @MockitoBean private VisionAnalysisPort visionAnalysisPort;
    @MockitoBean private SubscriptionVerificationRewardService rewardService;

    private final List<Member> participants = new ArrayList<>();
    private final List<SubscriptionVerification> verifications = new ArrayList<>();
    private CountDownLatch firstAnalysisStarted;
    private CountDownLatch releaseFirstAnalysis;
    private CountDownLatch analysesFinished;
    private Member owner;
    private Creator creator;
    private Mission mission;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(new Member("async-owner-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "async-creator-" + suffix));
        mission = missionRepository.saveAndFlush(new Mission(
                creator.getCreatorId(), MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null));
        firstAnalysisStarted = new CountDownLatch(1);
        releaseFirstAnalysis = new CountDownLatch(1);
        analysesFinished = new CountDownLatch(2);
        AtomicBoolean firstAnalysis = new AtomicBoolean(true);
        given(visionAnalysisPort.analyze(any())).willAnswer(invocation -> {
            if (firstAnalysis.compareAndSet(true, false)) {
                firstAnalysisStarted.countDown();
                await(releaseFirstAnalysis);
            }
            analysesFinished.countDown();
            return subscribedResult();
        });
    }

    @AfterEach
    void cleanUp() {
        releaseFirstAnalysis.countDown();
        for (SubscriptionVerification verification : verifications) {
            objectStorage.delete(verification.getImageObjectKey());
            verificationRepository.deleteById(verification.getVerificationId());
        }
        verificationRepository.flush();
        for (Member participant : participants) {
            memberRepository.deleteById(participant.getMemberId());
        }
        if (mission != null) {
            missionRepository.deleteById(mission.getMissionId());
        }
        if (creator != null) {
            creatorRepository.deleteById(creator.getCreatorId());
        }
        if (owner != null) {
            memberRepository.deleteById(owner.getMemberId());
        }
    }

    @Test
    void Trigger는_Provider_호출이_막혀도_즉시_반환하고_포화_거절_Verification은_PENDING과_Object를_유지한다()
            throws Exception {
        SubscriptionVerification first = pendingVerification();
        SubscriptionVerification queued = pendingVerification();
        SubscriptionVerification rejected = pendingVerification();

        long startedAt = System.nanoTime();
        trigger.trigger(first.getVerificationId());
        long triggerElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(triggerElapsedMillis).isLessThan(500);
        assertThat(firstAnalysisStarted.await(2, TimeUnit.SECONDS)).isTrue();

        trigger.trigger(queued.getVerificationId());
        trigger.trigger(rejected.getVerificationId());

        SubscriptionVerification persistedRejected = verificationRepository
                .findById(rejected.getVerificationId()).orElseThrow();
        assertThat(persistedRejected.getStatus()).isEqualTo(SubscriptionVerificationStatus.PENDING);
        assertThat(objectStorage.get(persistedRejected.getImageObjectKey())).isNotEmpty();

        releaseFirstAnalysis.countDown();
        assertThat(analysesFinished.await(5, TimeUnit.SECONDS)).isTrue();
        awaitTerminal(first.getVerificationId());
        awaitTerminal(queued.getVerificationId());
    }

    private SubscriptionVerification pendingVerification() {
        String suffix = UUID.randomUUID().toString();
        Member participant = memberRepository.saveAndFlush(
                new Member("async-user-" + suffix, null, null, MemberRole.USER));
        participants.add(participant);
        String objectKey = "subscription-verifications/test/" + suffix + "/image.jpg";
        objectStorage.put(objectKey, new byte[] {1, 2, 3}, "image/jpeg");
        SubscriptionVerification verification = verificationRepository.saveAndFlush(SubscriptionVerification.pending(
                participant.getMemberId(),
                creator.getCreatorId(),
                mission.getMissionId(),
                UUID.randomUUID().toString(),
                "a".repeat(64),
                "구독 인증 채널",
                "@async_channel",
                objectKey,
                "b".repeat(64),
                "JPEG_V1",
                UUID.randomUUID().toString(),
                Instant.now()));
        verifications.add(verification);
        return verification;
    }

    private void awaitTerminal(Long verificationId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            SubscriptionVerificationStatus status = verificationRepository.findById(verificationId)
                    .orElseThrow().getStatus();
            if (status != SubscriptionVerificationStatus.PENDING && status != SubscriptionVerificationStatus.PROCESSING) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("비동기 처리가 제한 시간 안에 종료되지 않았습니다. verificationId=" + verificationId);
    }

    private static VisionAnalysisResult subscribedResult() {
        return new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "구독 인증 채널",
                "@async_channel",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                0.9);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("비동기 Provider 호출 해제 대기 시간이 초과되었습니다.");
        }
    }
}

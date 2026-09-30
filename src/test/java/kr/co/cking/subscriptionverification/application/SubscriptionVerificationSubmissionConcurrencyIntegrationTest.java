package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageReuseRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageHashLockRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = "cking.verification.youtube-subscription.submission-enabled=true")
@Import(SubscriptionVerificationSubmissionConcurrencyIntegrationTest.TriggerFailureTestConfig.class)
class SubscriptionVerificationSubmissionConcurrencyIntegrationTest {

    @Autowired
    private SubscriptionVerificationSubmissionService submissionService;

    @Autowired
    private SubscriptionVerificationRepository verificationRepository;

    @Autowired
    private SubscriptionVerificationImageReuseRepository imageReuseRepository;

    @Autowired
    private SubscriptionVerificationImageHashLockRepository hashLockRepository;

    @Autowired
    private CreatorYoutubeChannelRepository channelRepository;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ObjectStorage objectStorage;

    private Member owner;
    private Member participant;
    private Creator creator;
    private Mission mission;
    private Member otherOwner;
    private Member otherParticipant;
    private Creator otherCreator;
    private Mission otherMission;
    private byte[] image;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(
                new Member("subscription-owner-" + suffix, null, null, MemberRole.USER));
        participant = memberRepository.saveAndFlush(
                new Member("subscription-user-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), "subscription-creator-" + suffix));
        mission = missionRepository.saveAndFlush(new Mission(
                creator.getCreatorId(), MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null));
        channelRepository.saveAndFlush(new CreatorYoutubeChannel(
                creator.getCreatorId(),
                "구독 인증 채널",
                "@subscription_" + suffix,
                java.time.Instant.now()));
        otherOwner = memberRepository.saveAndFlush(
                new Member("subscription-other-owner-" + suffix, null, null, MemberRole.USER));
        otherParticipant = memberRepository.saveAndFlush(
                new Member("subscription-other-user-" + suffix, null, null, MemberRole.USER));
        otherCreator = creatorRepository.saveAndFlush(
                new Creator(otherOwner.getMemberId(), "subscription-other-creator-" + suffix));
        otherMission = missionRepository.saveAndFlush(new Mission(
                otherCreator.getCreatorId(), MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null));
        channelRepository.saveAndFlush(new CreatorYoutubeChannel(
                otherCreator.getCreatorId(),
                "다른 구독 인증 채널",
                "@subscription_other_" + suffix,
                java.time.Instant.now()));
        image = validImage(suffix.hashCode());
    }

    @AfterEach
    void cleanUp() {
        List<SubscriptionVerification> verifications = new java.util.ArrayList<>();
        if (participant != null && creator != null && mission != null) {
            verifications.addAll(verificationRepository.findAllByMemberIdAndCreatorIdAndMissionId(
                    participant.getMemberId(), creator.getCreatorId(), mission.getMissionId()));
        }
        if (otherParticipant != null && otherCreator != null && otherMission != null) {
            verifications.addAll(verificationRepository.findAllByMemberIdAndCreatorIdAndMissionId(
                    otherParticipant.getMemberId(), otherCreator.getCreatorId(), otherMission.getMissionId()));
        }
        if (!verifications.isEmpty()) {
            verifications.forEach(verification -> objectStorage.delete(verification.getImageObjectKey()));
            imageReuseRepository.deleteAllById(verifications.stream()
                    .map(SubscriptionVerification::getVerificationId).toList());
            imageReuseRepository.flush();
            verificationRepository.deleteAll(verifications);
            verificationRepository.flush();
            hashLockRepository.deleteAllById(verifications.stream()
                    .map(SubscriptionVerification::getImageSha256).distinct().toList());
            hashLockRepository.flush();
        }
        if (otherCreator != null) {
            channelRepository.deleteById(otherCreator.getCreatorId());
        }
        if (otherMission != null) {
            missionRepository.deleteById(otherMission.getMissionId());
        }
        if (otherCreator != null) {
            creatorRepository.deleteById(otherCreator.getCreatorId());
        }
        if (otherParticipant != null) {
            memberRepository.deleteById(otherParticipant.getMemberId());
        }
        if (otherOwner != null) {
            memberRepository.deleteById(otherOwner.getMemberId());
        }
        if (creator != null) {
            channelRepository.deleteById(creator.getCreatorId());
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
    void 동일_requestId의_동시_요청은_하나의_Verification으로_수렴한다() throws Exception {
        UUID requestId = UUID.randomUUID();

        List<Object> results = executeConcurrently(requestId, requestId);

        assertThat(results).allMatch(SubscriptionVerificationSubmissionResult.class::isInstance);
        assertThat(results.stream()
                        .map(SubscriptionVerificationSubmissionResult.class::cast)
                        .filter(SubscriptionVerificationSubmissionResult::created))
                .hasSize(1);
        assertThat(verificationRepository.findByRequestId(requestId.toString())).isPresent();
    }

    @Test
    void 서로_다른_requestId의_동시_요청은_활성_Verification_하나만_생성한다() throws Exception {
        List<Object> results = executeConcurrently(UUID.randomUUID(), UUID.randomUUID());

        assertThat(results).filteredOn(SubscriptionVerificationSubmissionResult.class::isInstance)
                .hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .singleElement()
                .satisfies(result -> assertThat(((BusinessException) result).getErrorCode())
                        .isEqualTo(SubscriptionVerificationErrorCode.VERIFICATION_IN_PROGRESS));
        assertThat(verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        participant.getMemberId(), creator.getCreatorId(), mission.getMissionId()))
                .isPresent();
    }

    @Test
    void AFTER_COMMIT_Trigger가_실패해도_PENDING_Verification과_Object를_유지한다() {
        UUID requestId = UUID.randomUUID();

        SubscriptionVerificationSubmissionResult result = submissionService.submit(
                new SubscriptionVerificationSubmissionCommand(
                        participant.getMemberId(),
                        creator.getCreatorId(),
                        mission.getMissionId(),
                        requestId,
                        image));

        assertThat(result.created()).isTrue();
        SubscriptionVerification persisted = verificationRepository.findByRequestId(requestId.toString())
                .orElseThrow();
        assertThat(persisted.getStatus())
                .isEqualTo(kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus.PENDING);
        assertThat(objectStorage.get(persisted.getImageObjectKey())).isNotEmpty();
    }

    /** 다른 사용자·Creator의 동일 이미지 동시 제출은 최초 한 건과 재사용 한 건으로 기록한다. */
    @Test
    void 동일_이미지_동시_제출은_재사용_감사_기록으로_수렴한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SubscriptionVerificationSubmissionResult> first = executor.submit(() -> {
                start.await();
                return submit(participant, creator, mission, UUID.randomUUID());
            });
            Future<SubscriptionVerificationSubmissionResult> second = executor.submit(() -> {
                start.await();
                return submit(otherParticipant, otherCreator, otherMission, UUID.randomUUID());
            });
            start.countDown();
            List<Long> verificationIds = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
                    .stream().map(result -> result.verification().getVerificationId()).toList();

            assertThat(imageReuseRepository.findAllById(verificationIds))
                    .extracting(reuse -> reuse.getReuseType())
                    .containsExactlyInAnyOrder(
                            SubscriptionVerificationImageReuseType.FIRST_USE,
                            SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER);
            assertThat(imageReuseRepository.findAllById(verificationIds))
                    .filteredOn(reuse -> reuse.getReuseType()
                            == SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER)
                    .singleElement()
                    .satisfies(reuse -> assertThat(reuse.getMatchedVerificationId()).isIn(verificationIds));
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Object> executeConcurrently(UUID firstRequestId, UUID secondRequestId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> submitAfter(start, firstRequestId));
            Future<Object> second = executor.submit(() -> submitAfter(start, secondRequestId));
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Object submitAfter(CountDownLatch start, UUID requestId) throws InterruptedException {
        start.await();
        try {
            return submissionService.submit(new SubscriptionVerificationSubmissionCommand(
                    participant.getMemberId(),
                    creator.getCreatorId(),
                    mission.getMissionId(),
                    requestId,
                    image));
        } catch (BusinessException exception) {
            return exception;
        }
    }

    /** 각 사용자·Creator·Mission 조합으로 구독 인증 이미지를 제출한다. */
    private SubscriptionVerificationSubmissionResult submit(
            Member targetParticipant,
            Creator targetCreator,
            Mission targetMission,
            UUID requestId
    ) {
        return submissionService.submit(new SubscriptionVerificationSubmissionCommand(
                targetParticipant.getMemberId(), targetCreator.getCreatorId(), targetMission.getMissionId(),
                requestId, image));
    }

    /** 테스트 실행마다 구분되는 정규화 이미지를 만들어 이전 실행 이력과 격리한다. */
    private byte[] validImage(int rgb) throws Exception {
        BufferedImage bufferedImage = new BufferedImage(480, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bufferedImage.createGraphics();
        try {
            graphics.setColor(new Color(rgb & 0x00FFFFFF));
            graphics.fillRect(0, 0, 480, 480);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "png", output);
            return output.toByteArray();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TriggerFailureTestConfig {

        @Bean
        @Primary
        SubscriptionVerificationProcessingTrigger failingSubscriptionVerificationProcessingTrigger() {
            return verificationId -> {
                throw new IllegalStateException("queue rejected");
            };
        }
    }
}

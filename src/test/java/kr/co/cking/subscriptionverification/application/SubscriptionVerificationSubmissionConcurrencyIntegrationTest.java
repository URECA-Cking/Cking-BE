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
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "cking.verification.youtube-subscription.submission-enabled=true")
class SubscriptionVerificationSubmissionConcurrencyIntegrationTest {

    @Autowired
    private SubscriptionVerificationSubmissionService submissionService;

    @Autowired
    private SubscriptionVerificationRepository verificationRepository;

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
        image = validImage();
    }

    @AfterEach
    void cleanUp() {
        if (participant != null && creator != null && mission != null) {
            List<SubscriptionVerification> verifications =
                    verificationRepository.findAllByMemberIdAndCreatorIdAndMissionId(
                            participant.getMemberId(), creator.getCreatorId(), mission.getMissionId());
            verifications.forEach(verification -> objectStorage.delete(verification.getImageObjectKey()));
            verificationRepository.deleteAll(verifications);
            verificationRepository.flush();
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

    private byte[] validImage() throws Exception {
        BufferedImage bufferedImage = new BufferedImage(480, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bufferedImage.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, 480, 480);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "png", output);
            return output.toByteArray();
        }
    }
}

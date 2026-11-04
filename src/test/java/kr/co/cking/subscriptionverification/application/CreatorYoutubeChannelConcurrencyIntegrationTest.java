package kr.co.cking.subscriptionverification.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CreatorYoutubeChannelConcurrencyIntegrationTest {

    @Autowired
    private CreatorYoutubeChannelService channelService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorYoutubeChannelRepository channelRepository;
    @Autowired
    private MissionRepository missionRepository;

    private Member owner;
    private Creator creator;
    private Member otherOwner;
    private Creator otherCreator;

    @AfterEach
    void cleanUp() {
        if (creator != null) {
            channelRepository.deleteById(creator.getCreatorId());
            List<Mission> missions = missionRepository.findByCreatorIdAndTypeIn(
                    creator.getCreatorId(), List.of(MissionType.YOUTUBE_SUBSCRIPTION));
            missionRepository.deleteAll(missions);
            creatorRepository.deleteById(creator.getCreatorId());
        }
        if (owner != null) {
            memberRepository.deleteById(owner.getMemberId());
        }
        if (otherCreator != null) {
            channelRepository.deleteById(otherCreator.getCreatorId());
            missionRepository.deleteAll(missionRepository.findByCreatorIdAndTypeIn(
                    otherCreator.getCreatorId(), List.of(MissionType.YOUTUBE_SUBSCRIPTION)));
            creatorRepository.deleteById(otherCreator.getCreatorId());
        }
        if (otherOwner != null) {
            memberRepository.deleteById(otherOwner.getMemberId());
        }
    }

    @Test
    void 동시_최초_설정에도_채널과_구독_미션은_각각_하나만_생성된다() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(new Member("channel-owner-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix));
        CreatorYoutubeChannelCommand command = new CreatorYoutubeChannelCommand(
                "동시성 채널", "@concurrent_" + suffix);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CreatorYoutubeChannelUpsertResult> first = executor.submit(() -> putAfter(start, command));
            Future<CreatorYoutubeChannelUpsertResult> second = executor.submit(() -> putAfter(start, command));
            start.countDown();

            List<CreatorYoutubeChannelUpsertResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(CreatorYoutubeChannelUpsertResult::created).hasSize(1);
            assertThat(channelRepository.findById(creator.getCreatorId())).isPresent();
            assertThat(missionRepository.findByCreatorIdAndTypeIn(
                    creator.getCreatorId(), List.of(MissionType.YOUTUBE_SUBSCRIPTION))).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 같은_YouTube_채널_ID는_다른_Creator가_동시에_연결해도_하나만_성공한다() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(new Member("channel-owner-a-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-a-" + suffix));
        otherOwner = memberRepository.saveAndFlush(new Member("channel-owner-b-" + suffix, null, null, MemberRole.USER));
        otherCreator = creatorRepository.saveAndFlush(new Creator(otherOwner.getMemberId(), "creator-b-" + suffix));
        String channelId = "UC" + suffix + "0000000000";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> connectAfter(start,
                    owner.getMemberId(), creator.getCreatorId(), channelId, "@same_" + suffix));
            Future<Boolean> second = executor.submit(() -> connectAfter(start,
                    otherOwner.getMemberId(), otherCreator.getCreatorId(), channelId, "@same_" + suffix));
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(java.util.stream.Stream.of(creator.getCreatorId(), otherCreator.getCreatorId())
                    .map(channelRepository::findById)
                    .flatMap(java.util.Optional::stream)
                    .filter(channel -> channelId.equals(channel.getYoutubeChannelId()))
                    .count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean connectAfter(CountDownLatch start, Long memberId, Long creatorId,
            String channelId, String handle) throws InterruptedException {
        start.await();
        try {
            channelService.connectVerified(memberId, creatorId, channelId, "공식 채널", handle);
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(
                    SubscriptionVerificationErrorCode.CHANNEL_ALREADY_CONNECTED);
            return false;
        }
    }

    private CreatorYoutubeChannelUpsertResult putAfter(
            CountDownLatch start,
            CreatorYoutubeChannelCommand command
    ) throws InterruptedException {
        start.await();
        return channelService.put(owner.getMemberId(), command);
    }
}

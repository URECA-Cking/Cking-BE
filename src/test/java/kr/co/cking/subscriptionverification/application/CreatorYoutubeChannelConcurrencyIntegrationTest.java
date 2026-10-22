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

    private CreatorYoutubeChannelUpsertResult putAfter(
            CountDownLatch start,
            CreatorYoutubeChannelCommand command
    ) throws InterruptedException {
        start.await();
        return channelService.put(owner.getMemberId(), command);
    }
}

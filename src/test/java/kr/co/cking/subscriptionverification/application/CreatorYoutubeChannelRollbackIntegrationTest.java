package kr.co.cking.subscriptionverification.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.YoutubeSubscriptionMissionProvisioningService;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

@SpringBootTest
class CreatorYoutubeChannelRollbackIntegrationTest {

    @Autowired
    private CreatorYoutubeChannelService channelService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorYoutubeChannelRepository channelRepository;

    @MockitoBean
    private YoutubeSubscriptionMissionProvisioningService missionProvisioningService;

    private Member owner;
    private Creator creator;

    @AfterEach
    void cleanUp() {
        if (creator != null) {
            channelRepository.deleteById(creator.getCreatorId());
            creatorRepository.deleteById(creator.getCreatorId());
        }
        if (owner != null) {
            memberRepository.deleteById(owner.getMemberId());
        }
    }

    @Test
    void 미션_생성이_실패하면_먼저_flush한_채널도_rollback된다() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        owner = memberRepository.saveAndFlush(new Member("rollback-owner-" + suffix, null, null, MemberRole.USER));
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "rollback-creator-" + suffix));
        willThrow(new IllegalStateException("미션 생성 실패"))
                .given(missionProvisioningService).provision(any());

        assertThatThrownBy(() -> channelService.put(
                owner.getMemberId(), new CreatorYoutubeChannelCommand("롤백 채널", "@rollback_" + suffix)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(channelRepository.findById(creator.getCreatorId())).isEmpty();
    }
}

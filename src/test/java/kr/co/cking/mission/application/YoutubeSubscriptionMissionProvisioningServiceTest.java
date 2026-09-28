package kr.co.cking.mission.application;

import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YoutubeSubscriptionMissionProvisioningServiceTest {

    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final YoutubeSubscriptionMissionProvisioningService service =
            new YoutubeSubscriptionMissionProvisioningService(missionRepository);

    @Test
    void 구독_미션이_없으면_보상_1장_상시_활성으로_생성한다() {
        when(missionRepository.findByCreatorIdAndType(3L, MissionType.YOUTUBE_SUBSCRIPTION))
                .thenReturn(Optional.empty());
        when(missionRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Mission result = service.provision(3L);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        verify(missionRepository).save(captor.capture());
        assertThat(result).isSameAs(captor.getValue());
        assertThat(result.getCreatorId()).isEqualTo(3L);
        assertThat(result.getType()).isEqualTo(MissionType.YOUTUBE_SUBSCRIPTION);
        assertThat(result.getRewardAmount()).isEqualTo(1);
        assertThat(result.getActiveFrom()).isNull();
        assertThat(result.getActiveTo()).isNull();
    }

    @Test
    void 구독_미션이_있으면_기존_미션을_반환한다() {
        Mission existing = new Mission(3L, MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null);
        when(missionRepository.findByCreatorIdAndType(3L, MissionType.YOUTUBE_SUBSCRIPTION))
                .thenReturn(Optional.of(existing));

        assertThat(service.provision(3L)).isSameAs(existing);
        verify(missionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}

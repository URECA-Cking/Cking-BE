package kr.co.cking.mission.application;

import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionInitializationServiceTest {

    private static final Long CREATOR_ID = 10L;

    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final MissionInitializationService service = new MissionInitializationService(missionRepository);

    @Test
    void 미션이_하나도_없으면_출석과_좋아요를_각각_보상_1장_상시_활성으로_생성한다() {
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), any())).thenReturn(List.of());

        service.initializeDefaultMissions(CREATOR_ID);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        verify(missionRepository, times(2)).save(captor.capture());
        List<Mission> saved = captor.getAllValues();

        assertThat(saved).extracting(Mission::getType)
                .containsExactlyInAnyOrder(MissionType.ATTENDANCE, MissionType.LIKE);
        assertThat(saved).allSatisfy(mission -> {
            assertThat(mission.getCreatorId()).isEqualTo(CREATOR_ID);
            assertThat(mission.getRewardAmount()).isEqualTo(1);
            assertThat(mission.getActiveFrom()).isNull();
            assertThat(mission.getActiveTo()).isNull();
        });
    }

    @Test
    void 이미_출석_미션이_있으면_좋아요만_생성한다() {
        Mission existingAttendance = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1, null, null);
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(existingAttendance));

        service.initializeDefaultMissions(CREATOR_ID);

        verify(missionRepository, times(1)).save(argThat(mission -> mission.getType() == MissionType.LIKE));
        verify(missionRepository, never()).save(argThat(mission -> mission.getType() == MissionType.ATTENDANCE));
    }

    @Test
    void 출석과_좋아요가_모두_있으면_아무것도_생성하지_않는다() {
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(
                        new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1, null, null),
                        new Mission(CREATOR_ID, MissionType.LIKE, 1, null, null)));

        service.initializeDefaultMissions(CREATOR_ID);

        verify(missionRepository, never()).save(any());
    }

    @Test
    void 조회는_출석과_좋아요_유형만_대상으로_한다() {
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), any())).thenReturn(List.of());

        service.initializeDefaultMissions(CREATOR_ID);

        ArgumentCaptor<java.util.Collection<MissionType>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(missionRepository).findByCreatorIdAndTypeIn(eq(CREATOR_ID), captor.capture());
        assertThat(Set.copyOf(captor.getValue())).isEqualTo(Set.of(MissionType.ATTENDANCE, MissionType.LIKE));
    }

    @Test
    void 새_트랜잭션_진입점도_동일하게_초기화한다() {
        // 전파(propagation) 차이는 실제 @Transactional 프록시가 있어야 검증되므로
        // (MissionBackfillRunnerIntegrationTest 참고), 여기서는 initializeDefaultMissions와
        // 같은 결과를 내는지(위임 로직 자체)만 확인한다.
        when(missionRepository.findByCreatorIdAndTypeIn(eq(CREATOR_ID), any())).thenReturn(List.of());

        service.initializeCreatorInNewTransaction(CREATOR_ID);

        verify(missionRepository, times(2)).save(any());
    }
}

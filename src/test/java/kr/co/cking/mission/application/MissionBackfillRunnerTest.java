package kr.co.cking.mission.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionBackfillRunnerTest {

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final MissionInitializationService missionInitializationService =
            mock(MissionInitializationService.class);
    private final MissionBackfillRunner runner =
            new MissionBackfillRunner(creatorRepository, missionInitializationService);

    @Test
    void 전체_creator를_조회해_각각_새_트랜잭션_초기화를_호출한다() {
        when(creatorRepository.findAll()).thenReturn(List.of(creatorWithId(1L), creatorWithId(2L)));

        runner.backfillAllCreators();

        verify(missionInitializationService).initializeCreatorInNewTransaction(eq(1L));
        verify(missionInitializationService).initializeCreatorInNewTransaction(eq(2L));
    }

    @Test
    void 한_creator가_실패해도_예외를_던지지_않고_나머지를_계속_처리한다() {
        doThrow(new RuntimeException("boom"))
                .when(missionInitializationService).initializeCreatorInNewTransaction(eq(1L));

        assertThatCode(() -> runner.backfillCreators(List.of(1L, 2L, 3L)))
                .doesNotThrowAnyException();

        verify(missionInitializationService).initializeCreatorInNewTransaction(eq(1L));
        verify(missionInitializationService).initializeCreatorInNewTransaction(eq(2L));
        verify(missionInitializationService).initializeCreatorInNewTransaction(eq(3L));
    }

    private Creator creatorWithId(Long creatorId) {
        Creator creator = new Creator(100L, "creator-" + creatorId);
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }
}

package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kr.co.cking.creator.repository.CreatorRepository;
import org.junit.jupiter.api.Test;

class CreatorQueryServiceTest {

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorQueryService creatorQueryService = new CreatorQueryService(creatorRepository);

    @Test
    void Member_ID로_Creator_연결_여부를_조회한다() {
        when(creatorRepository.existsByMemberId(1L)).thenReturn(true);

        assertThat(creatorQueryService.isCreatorMember(1L)).isTrue();

        verify(creatorRepository).existsByMemberId(1L);
    }
}

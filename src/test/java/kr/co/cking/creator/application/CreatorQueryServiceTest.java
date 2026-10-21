package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorQueryServiceTest {

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorQueryService creatorQueryService = new CreatorQueryService(creatorRepository);

    @Test
    void Member_ID로_Creator_연결_여부를_조회한다() {
        when(creatorRepository.existsByMemberId(1L)).thenReturn(true);

        assertThat(creatorQueryService.isCreatorMember(1L)).isTrue();

        verify(creatorRepository).existsByMemberId(1L);
    }

    @Test
    void 활동명_미설정_Creator의_공개_활동_검증은_설정_필요_오류를_반환한다() {
        Creator creator = new Creator(1L, "creator-1");
        ReflectionTestUtils.setField(creator, "activityNameConfigured", false);
        when(creatorRepository.findByMemberId(1L)).thenReturn(Optional.of(creator));

        assertThatThrownBy(() -> creatorQueryService.requireActivityNameConfiguredForCreator(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED);
    }
}

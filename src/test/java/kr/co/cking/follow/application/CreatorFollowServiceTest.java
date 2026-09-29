package kr.co.cking.follow.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorFollowServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final Long MEMBER_ID = 10L;
    private static final Long CREATOR_ID = 1L;
    private static final Long CREATOR_OWNER_MEMBER_ID = 99L;

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorFollowRepository followRepository = mock(CreatorFollowRepository.class);
    private final CreatorFollowService service = new CreatorFollowService(
            creatorRepository, followRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 팔로우는_관계가_없을_때만_추가하는_insert를_호출한다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator()));

        service.follow(MEMBER_ID, CREATOR_ID);

        verify(followRepository).insertIfAbsent(MEMBER_ID, CREATOR_ID, NOW);
    }

    @Test
    void 존재하지_않는_크리에이터는_팔로우할_수_없다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.follow(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(followRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 본인_크리에이터는_팔로우할_수_없다() {
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator()));

        assertThatThrownBy(() -> service.follow(CREATOR_OWNER_MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(FollowErrorCode.SELF_FOLLOW_NOT_ALLOWED));
        verify(followRepository, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 언팔로우는_관계를_삭제한다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);

        service.unfollow(MEMBER_ID, CREATOR_ID);

        verify(followRepository).deleteByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID);
    }

    @Test
    void 존재하지_않는_크리에이터는_언팔로우할_수_없다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(false);

        assertThatThrownBy(() -> service.unfollow(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(followRepository, never()).deleteByMemberIdAndCreatorId(anyLong(), anyLong());
    }

    private Creator creator() {
        Creator creator = new Creator(CREATOR_OWNER_MEMBER_ID, "크리에이터");
        ReflectionTestUtils.setField(creator, "creatorId", CREATOR_ID);
        return creator;
    }
}

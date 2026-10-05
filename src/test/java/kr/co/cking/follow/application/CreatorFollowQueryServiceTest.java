package kr.co.cking.follow.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.dto.FollowedCreatorView;
import kr.co.cking.follow.domain.CreatorFollow;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorFollowQueryServiceTest {

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorFollowRepository followRepository = mock(CreatorFollowRepository.class);
    private final CreatorFollowQueryService service =
            new CreatorFollowQueryService(creatorRepository, followRepository);

    @Test
    void 비로그인_사용자는_팔로우하지_않은_것으로_본다() {
        assertThat(service.isFollowing(null, 1L)).isFalse();

        verify(followRepository, never()).existsByMemberIdAndCreatorId(any(), anyLong());
    }

    @Test
    void 팔로우_여부는_관계_존재_여부다() {
        given(followRepository.existsByMemberIdAndCreatorId(10L, 1L)).willReturn(true);

        assertThat(service.isFollowing(10L, 1L)).isTrue();
        assertThat(service.isFollowing(10L, 2L)).isFalse();
    }

    @Test
    void API_팔로우_여부_조회는_존재하지_않는_크리에이터면_404다() {
        given(creatorRepository.existsById(1L)).willReturn(false);

        assertThatThrownBy(() -> service.findFollowStatus(10L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 내_팔로우_목록은_팔로우_순서를_유지하고_크리에이터_이름을_붙인다() {
        Instant later = Instant.parse("2026-09-29T02:00:00Z");
        Instant earlier = Instant.parse("2026-09-29T01:00:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        given(followRepository.findByMemberIdLatestFirst(10L, pageable)).willReturn(new PageImpl<>(
                List.of(follow(2L, later), follow(1L, earlier)), pageable, 2));
        given(creatorRepository.findByCreatorIdIn(List.of(2L, 1L)))
                .willReturn(List.of(creator(1L, "첫번째"), creator(2L, "두번째")));

        Page<FollowedCreatorView> result = service.findMine(10L, pageable);

        assertThat(result.getContent()).containsExactly(
                new FollowedCreatorView(2L, "두번째", later),
                new FollowedCreatorView(1L, "첫번째", earlier));
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 다른_도메인용_팔로우_creatorId를_한번에_조회한다() {
        given(followRepository.findCreatorIdsByMemberIdOrderByCreatorIdAsc(10L))
                .willReturn(List.of(1L, 2L));

        assertThat(service.findFollowedCreatorIds(10L)).containsExactly(1L, 2L);
    }

    private CreatorFollow follow(Long creatorId, Instant createdAt) {
        CreatorFollow follow = instantiate();
        ReflectionTestUtils.setField(follow, "memberId", 10L);
        ReflectionTestUtils.setField(follow, "creatorId", creatorId);
        ReflectionTestUtils.setField(follow, "createdAt", createdAt);
        return follow;
    }

    private CreatorFollow instantiate() {
        try {
            var constructor = CreatorFollow.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Creator creator(Long creatorId, String name) {
        Creator creator = new Creator(100L + creatorId, name);
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }
}

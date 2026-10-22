package kr.co.cking.creator.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CreatorPublicProfileQueryServiceTest {

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSpaceRepository creatorSpaceRepository = mock(CreatorSpaceRepository.class);
    private final CreatorPublicProfileQueryService service =
            new CreatorPublicProfileQueryService(creatorRepository, creatorSpaceRepository);

    @Test
    void ID가_없으면_조회하지_않고_빈_Map이다() {
        assertThat(service.findByCreatorIds(List.of())).isEmpty();

        verify(creatorRepository, never()).findByCreatorIdIn(any());
        verify(creatorSpaceRepository, never()).findByCreatorIdIn(any());
    }

    @Test
    void 활동명과_Space_프로필_이미지를_Creator_ID별로_한_번씩만_조회해_채운다() {
        List<Long> ids = List.of(1L, 2L);
        Creator first = creator(1L, "첫째");
        Creator second = creator(2L, "둘째");
        CreatorSpace firstSpace = space(1L, "https://img/1");
        given(creatorRepository.findByCreatorIdIn(ids)).willReturn(List.of(first, second));
        given(creatorSpaceRepository.findByCreatorIdIn(ids)).willReturn(List.of(firstSpace));

        Map<Long, CreatorPublicProfile> result = service.findByCreatorIds(ids);

        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(1L)).isEqualTo(new CreatorPublicProfile(1L, "첫째", "https://img/1"));
        // Space가 없는 Creator는 이름은 있고 이미지만 null이다.
        assertThat(result.get(2L)).isEqualTo(new CreatorPublicProfile(2L, "둘째", null));
        verify(creatorRepository, times(1)).findByCreatorIdIn(ids);
        verify(creatorSpaceRepository, times(1)).findByCreatorIdIn(ids);
    }

    @Test
    void 존재하지_않는_Creator는_Map에_없다() {
        List<Long> ids = List.of(1L, 2L);
        Creator first = creator(1L, "첫째");
        CreatorSpace orphanSpace = space(2L, "https://img/2");
        given(creatorRepository.findByCreatorIdIn(ids)).willReturn(List.of(first));
        given(creatorSpaceRepository.findByCreatorIdIn(ids)).willReturn(List.of(orphanSpace));

        Map<Long, CreatorPublicProfile> result = service.findByCreatorIds(ids);

        // Space만 있고 Creator 행이 없는 ID(2)는 제외된다.
        assertThat(result).containsOnlyKeys(1L);
    }

    @Test
    void Member_ID로도_활동명과_Space_프로필_사진을_일괄_조회한다() {
        List<Long> memberIds = List.of(10L, 20L);
        Creator first = creator(1L, 10L, "첫째");
        Creator second = creator(2L, 20L, "둘째");
        CreatorSpace secondSpace = space(2L, "https://img/2");
        given(creatorRepository.findByMemberIdIn(memberIds)).willReturn(List.of(first, second));
        given(creatorSpaceRepository.findByCreatorIdIn(List.of(1L, 2L)))
                .willReturn(List.of(secondSpace));

        Map<Long, CreatorPublicProfile> result = service.findByMemberIds(memberIds);

        assertThat(result).containsOnlyKeys(10L, 20L);
        assertThat(result.get(10L)).isEqualTo(new CreatorPublicProfile(1L, "첫째", null));
        assertThat(result.get(20L)).isEqualTo(new CreatorPublicProfile(2L, "둘째", "https://img/2"));
        verify(creatorRepository).findByMemberIdIn(memberIds);
        verify(creatorSpaceRepository).findByCreatorIdIn(List.of(1L, 2L));
    }

    private static Creator creator(long creatorId, String name) {
        return creator(creatorId, creatorId, name);
    }

    private static Creator creator(long creatorId, long memberId, String name) {
        Creator creator = mock(Creator.class);
        given(creator.getCreatorId()).willReturn(creatorId);
        given(creator.getMemberId()).willReturn(memberId);
        given(creator.publicDisplayName()).willReturn(name);
        return creator;
    }

    private static CreatorSpace space(long creatorId, String profileImageUrl) {
        CreatorSpace space = mock(CreatorSpace.class);
        given(space.getCreatorId()).willReturn(creatorId);
        given(space.getProfileImageUrl()).willReturn(profileImageUrl);
        return space;
    }
}

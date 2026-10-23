package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceFixtures;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorSpaceProfileImageLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    void 본인이_올린_완료_이미지를_연결하고_기존_이미지는_삭제_대기로_바꾼다() {
        CreatorRepository creators = org.mockito.Mockito.mock(CreatorRepository.class);
        CreatorSpaceRepository spaces = org.mockito.Mockito.mock(CreatorSpaceRepository.class);
        CreatorSpaceProfileImageRepository images = org.mockito.Mockito.mock(CreatorSpaceProfileImageRepository.class);
        Creator creator = creator(7L, 42L);
        CreatorSpace space = CreatorSpaceFixtures.space(42L, "소개", "https://img/old.jpg", "https://img/banner.jpg", "creator-42");
        space.updateProfileImage("creator-space-profile-images/42/old.jpg", "https://cdn.example/creator-space-profile-images/42/old.jpg");
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaces.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(space));
        given(images.link("creator-space-profile-images/42/new.jpg", 42L, NOW)).willReturn(1);
        CreatorSpaceProfileImageManager manager = new CreatorSpaceProfileImageManager(
                images, new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        CreatorSpaceProfileImageLinkService service = new CreatorSpaceProfileImageLinkService(
                creators, spaces, manager);

        service.link(7L, "creator-space-profile-images/42/new.jpg");

        assertThat(space.getProfileImageKey()).isEqualTo("creator-space-profile-images/42/new.jpg");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://cdn.example/creator-space-profile-images/42/new.jpg");
        then(images).should().markDeletePending("creator-space-profile-images/42/old.jpg", 42L, NOW);
    }

    @Test
    void 다른_Creator의_이미지이거나_연결되지_않은_키는_거부한다() {
        CreatorRepository creators = org.mockito.Mockito.mock(CreatorRepository.class);
        CreatorSpaceRepository spaces = org.mockito.Mockito.mock(CreatorSpaceRepository.class);
        CreatorSpaceProfileImageRepository images = org.mockito.Mockito.mock(CreatorSpaceProfileImageRepository.class);
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator(7L, 42L)));
        given(spaces.findByCreatorIdForUpdate(42L)).willReturn(Optional.of(CreatorSpaceFixtures.space(42L, "소개", "https://img/profile.jpg", "https://img/banner.jpg", "creator-42")));
        given(images.link(any(), eq(42L), any())).willReturn(0);
        CreatorSpaceProfileImageManager manager = new CreatorSpaceProfileImageManager(
                images, new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        CreatorSpaceProfileImageLinkService service = new CreatorSpaceProfileImageLinkService(
                creators, spaces, manager);

        assertThatThrownBy(() -> service.link(7L, "creator-space-profile-images/99/other.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);
    }

    private Creator creator(Long memberId, Long creatorId) {
        Creator creator = new Creator(memberId, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }
}

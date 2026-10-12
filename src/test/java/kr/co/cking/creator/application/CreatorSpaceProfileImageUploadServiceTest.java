package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorSpaceProfileImageUploadServiceTest {
    @Test
    void Creator_소유_경로에_정규화한_JPEG를_저장한다() {
        CreatorRepository creators = org.mockito.Mockito.mock(CreatorRepository.class);
        CreatorSpaceRepository spaces = org.mockito.Mockito.mock(CreatorSpaceRepository.class);
        CreatorSpaceProfileImageRepository images = org.mockito.Mockito.mock(CreatorSpaceProfileImageRepository.class);
        ImageNormalizer normalizer = org.mockito.Mockito.mock(ImageNormalizer.class);
        ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);
        Creator creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaces.findByCreatorId(42L)).willReturn(Optional.of(CreatorSpace.fromTemplate(42L,
                new CreatorSpaceTemplate(1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"),
                "creator-42")));
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {1}, "a", "b", "v", 200, 200));
        CreatorSpaceProfileImageUploadService service = new CreatorSpaceProfileImageUploadService(
                creators, spaces, images, normalizer, storage, Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));

        String key = service.upload(7L, new byte[] {1});

        assertThat(key).matches("creator-space-profile-images/42/[0-9a-f-]{36}\\.jpg");
        then(storage).should().put(key, new byte[] {1}, "image/jpeg");
        then(images).should().markUploaded(any(), any());
    }

    @Test
    void Space가_없는_Creator의_업로드는_객체_저장_전에_404로_거절한다() {
        CreatorRepository creators = org.mockito.Mockito.mock(CreatorRepository.class);
        CreatorSpaceRepository spaces = org.mockito.Mockito.mock(CreatorSpaceRepository.class);
        CreatorSpaceProfileImageRepository images = org.mockito.Mockito.mock(CreatorSpaceProfileImageRepository.class);
        ImageNormalizer normalizer = org.mockito.Mockito.mock(ImageNormalizer.class);
        ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);
        Creator creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaces.findByCreatorId(42L)).willReturn(Optional.empty());
        CreatorSpaceProfileImageUploadService service = new CreatorSpaceProfileImageUploadService(
                creators, spaces, images, normalizer, storage, Clock.systemUTC());

        assertThatThrownBy(() -> service.upload(7L, new byte[] {1}))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(error -> ((kr.co.cking.common.exception.BusinessException) error).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND);
        then(normalizer).shouldHaveNoInteractions();
        then(images).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
    }
}

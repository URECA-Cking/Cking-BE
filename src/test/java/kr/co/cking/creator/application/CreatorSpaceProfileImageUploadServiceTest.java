package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreatorSpaceProfileImageUploadServiceTest {
    @Test
    void Creator_소유_경로에_정규화한_JPEG를_저장한다() {
        CreatorRepository creators = org.mockito.Mockito.mock(CreatorRepository.class);
        CreatorSpaceProfileImageRepository images = org.mockito.Mockito.mock(CreatorSpaceProfileImageRepository.class);
        ImageNormalizer normalizer = org.mockito.Mockito.mock(ImageNormalizer.class);
        ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);
        Creator creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {1}, "a", "b", "v", 200, 200));
        CreatorSpaceProfileImageUploadService service = new CreatorSpaceProfileImageUploadService(
                creators, images, normalizer, storage, Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));

        String key = service.upload(7L, new byte[] {1});

        assertThat(key).matches("creator-space-profile-images/42/[0-9a-f-]{36}\\.jpg");
        then(storage).should().put(key, new byte[] {1}, "image/jpeg");
        then(images).should().markUploaded(any(), any());
    }
}

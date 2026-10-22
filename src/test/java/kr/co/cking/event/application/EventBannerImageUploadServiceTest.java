package kr.co.cking.event.application;

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
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.domain.EventBannerImage;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventBannerImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EventBannerImageUploadServiceTest {

    private final CreatorRepository creators = org.mockito.Mockito.mock(CreatorRepository.class);
    private final EventBannerImageRepository images = org.mockito.Mockito.mock(EventBannerImageRepository.class);
    private final ImageNormalizer normalizer = org.mockito.Mockito.mock(ImageNormalizer.class);
    private final ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);
    private final EventBannerImageUploadService service = new EventBannerImageUploadService(
            creators, images, normalizer, storage, Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void CDN이_열린_Space_이미지_접두사_아래_Creator_경로에_배너_정책으로_정규화한_JPEG를_저장한다() {
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator(42L)));
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {1}, "a", "b", "v", 900, 500));

        String key = service.upload(7L, new byte[] {9});

        assertThat(key).matches("creator-space-profile-images/42/event-banner/[0-9a-f-]{36}\\.jpg");
        then(normalizer).should().normalize(new byte[] {9}, new ImagePolicy(600, 200, 2048));
        then(images).should().save(any(EventBannerImage.class));
        then(storage).should().put(key, new byte[] {1}, "image/jpeg");
        then(images).should().markUploaded(eq(key), any());
    }

    @Test
    void Creator가_아니면_정규화_저장_전에_FORBIDDEN으로_거절한다() {
        given(creators.findByMemberId(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(7L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        then(normalizer).shouldHaveNoInteractions();
        then(images).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
    }

    @Test
    void 형식_크기_해상도_규칙을_어긴_이미지는_기록_저장_없이_INVALID_EVENT_BANNER_IMAGE다() {
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator(42L)));
        given(normalizer.normalize(any(), any())).willThrow(new InvalidImageException());

        assertThatThrownBy(() -> service.upload(7L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EventErrorCode.INVALID_EVENT_BANNER_IMAGE);
        then(images).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
    }

    private Creator creator(Long creatorId) {
        Creator creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }
}

package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

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
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceFixtures;
import kr.co.cking.creator.domain.CreatorSpaceImageKind;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 배너 이미지 업로드(이슈 #590): 프로필 사진과 같은 접두사를 쓰되 하위 경로·정책·오류 코드·종류가 다르다. */
class CreatorSpaceBannerImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    private final CreatorRepository creators = mock(CreatorRepository.class);
    private final CreatorSpaceRepository spaces = mock(CreatorSpaceRepository.class);
    private final CreatorSpaceProfileImageRepository images = mock(CreatorSpaceProfileImageRepository.class);
    private final ImageNormalizer normalizer = mock(ImageNormalizer.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final CreatorSpaceProfileImageUploadService service = new CreatorSpaceProfileImageUploadService(
            creators, spaces, images, normalizer, storage, Clock.fixed(NOW, ZoneOffset.UTC));

    private Creator creator;

    @BeforeEach
    void setUp() {
        creator = new Creator(7L, "creator");
        ReflectionTestUtils.setField(creator, "creatorId", 42L);
    }

    private void givenCreatorWithSpace() {
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaces.findByCreatorId(42L)).willReturn(Optional.of(CreatorSpaceFixtures.space(42L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-42")));
    }

    @Test
    void 배너는_프로필과_같은_접두사의_banner_하위_경로에_정규화한_JPEG로_저장한다() {
        givenCreatorWithSpace();
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {9}, "a", "b", "v", 1200, 400));

        String key = service.uploadBanner(7L, new byte[] {1});

        assertThat(key).matches("creator-space-profile-images/42/banner/[0-9a-f-]{36}\\.jpg");
        then(storage).should().put(key, new byte[] {9}, "image/jpeg");
        then(images).should().save(argThat(image ->
                image.getObjectKey().equals(key)
                        && image.getCreatorId().equals(42L)
                        && image.getImageKind() == CreatorSpaceImageKind.BANNER));
        then(images).should().markUploaded(eq(key), any());
    }

    @Test
    void 배너는_프로필보다_넓은_최소_해상도_정책으로_정규화한다() {
        givenCreatorWithSpace();
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {9}, "a", "b", "v", 1200, 400));

        service.uploadBanner(7L, new byte[] {1});

        ArgumentCaptor<ImagePolicy> policy = ArgumentCaptor.forClass(ImagePolicy.class);
        then(normalizer).should().normalize(any(), policy.capture());
        assertThat(policy.getValue()).isEqualTo(new ImagePolicy(600, 200, 2048));
    }

    @Test
    void 프로필_사진_업로드는_그대로_프로필_종류와_경로를_쓴다() {
        givenCreatorWithSpace();
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {9}, "a", "b", "v", 300, 300));

        String key = service.upload(7L, new byte[] {1});

        assertThat(key).matches("creator-space-profile-images/42/[0-9a-f-]{36}\\.jpg");
        then(images).should().save(argThat(image -> image.getImageKind() == CreatorSpaceImageKind.PROFILE));
    }

    @Test
    void 이미지_검증에_실패하면_배너_오류_코드로_거절하고_저장하지_않는다() {
        givenCreatorWithSpace();
        given(normalizer.normalize(any(), any())).willThrow(new InvalidImageException());

        assertThatThrownBy(() -> service.uploadBanner(7L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);

        then(images).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
    }

    @Test
    void Space가_없는_Creator의_배너_업로드는_정규화_전에_404로_거절한다() {
        given(creators.findByMemberId(7L)).willReturn(Optional.of(creator));
        given(spaces.findByCreatorId(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadBanner(7L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        then(normalizer).shouldHaveNoInteractions();
        then(images).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
    }

    @Test
    void Creator가_아닌_회원의_배너_업로드는_FORBIDDEN이다() {
        given(creators.findByMemberId(8L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadBanner(8L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        then(storage).shouldHaveNoInteractions();
    }
}

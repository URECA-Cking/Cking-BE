package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

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
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpaceImageKind;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 관리자 템플릿 이미지 업로드(이슈 #593, #598): 관리자 전용이고, Creator 이미지와 같은 정책으로 올리되
 * Creator 소유 기록 대신 정리용 업로드 기록을 남긴다.
 */
class CreatorSpaceTemplateImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final MemberRepository members = mock(MemberRepository.class);
    private final CreatorSpaceTemplateImageRepository images = mock(CreatorSpaceTemplateImageRepository.class);
    private final ImageNormalizer normalizer = mock(ImageNormalizer.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final CreatorSpaceTemplateImageUploadService service = new CreatorSpaceTemplateImageUploadService(
            members, images, normalizer, storage,
            new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private void givenAdmin() {
        given(members.findById(1L)).willReturn(Optional.of(new Member("관리자", null, null, MemberRole.ADMIN)));
    }

    private void givenNormalizedImage() {
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {9}, "a", "b", "v", 1200, 400));
    }

    @Test
    void 배너는_template_banner_하위_경로에_JPEG로_저장하고_CDN_URL을_돌려준다() {
        givenAdmin();
        givenNormalizedImage();

        String url = service.uploadBanner(1L, new byte[] {1});

        assertThat(url).matches("https://cdn\\.example/creator-space-profile-images/template/banner/[0-9a-f-]{36}\\.jpg");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(storage).should().put(key.capture(), eq(new byte[] {9}), eq("image/jpeg"));
        assertThat(url).endsWith(key.getValue());
    }

    @Test
    void 프로필은_template_profile_하위_경로에_저장한다() {
        givenAdmin();
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {9}, "a", "b", "v", 300, 300));

        String url = service.uploadProfile(1L, new byte[] {1});

        assertThat(url).matches("https://cdn\\.example/creator-space-profile-images/template/profile/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    void 저장소에_올리기_전에_업로드_기록을_남기고_올린_뒤_완료로_표시한다() {
        givenAdmin();
        givenNormalizedImage();

        service.uploadBanner(1L, new byte[] {1});

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(images, storage);
        order.verify(images).save(argThat(image ->
                image.getObjectKey().matches("creator-space-profile-images/template/banner/[0-9a-f-]{36}\\.jpg")
                        && image.getImageKind() == CreatorSpaceImageKind.BANNER
                        && image.getCreatedAt().equals(NOW)));
        order.verify(storage).put(key.capture(), any(), eq("image/jpeg"));
        order.verify(images).markUploaded(eq(key.getValue()), eq(NOW));
    }

    @Test
    void 프로필_업로드_기록은_프로필_종류다() {
        givenAdmin();
        given(normalizer.normalize(any(), any())).willReturn(new NormalizedImage(new byte[] {9}, "a", "b", "v", 300, 300));

        service.uploadProfile(1L, new byte[] {1});

        then(images).should().save(argThat(image -> image.getImageKind() == CreatorSpaceImageKind.PROFILE
                && image.getObjectKey().contains("/template/profile/")));
    }

    @Test
    void Creator_이미지와_같은_해상도_정책으로_정규화한다() {
        givenAdmin();
        givenNormalizedImage();

        service.uploadBanner(1L, new byte[] {1});
        service.uploadProfile(1L, new byte[] {1});

        ArgumentCaptor<ImagePolicy> policy = ArgumentCaptor.forClass(ImagePolicy.class);
        then(normalizer).should(times(2)).normalize(any(), policy.capture());
        assertThat(policy.getAllValues()).containsExactly(new ImagePolicy(600, 200, 2048), new ImagePolicy(200, 200, 2048));
    }

    @Test
    void 관리자가_아니면_FORBIDDEN이고_정규화하거나_저장하거나_기록하지_않는다() {
        given(members.findById(2L)).willReturn(Optional.of(new Member("일반사용자", null, null, MemberRole.USER)));

        assertThatThrownBy(() -> service.uploadBanner(2L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        then(normalizer).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void 없는_회원은_RESOURCE_NOT_FOUND다() {
        given(members.findById(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadProfile(3L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        then(storage).shouldHaveNoInteractions();
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void 이미지_검증에_실패하면_템플릿_이미지_오류_코드로_거절하고_저장하거나_기록하지_않는다() {
        givenAdmin();
        given(normalizer.normalize(any(), any())).willThrow(new InvalidImageException());

        assertThatThrownBy(() -> service.uploadBanner(1L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);

        then(storage).shouldHaveNoInteractions();
        then(images).shouldHaveNoInteractions();
    }

    @Test
    void 저장소_업로드가_실패하면_완료_표시를_하지_않아_정리_대상으로_남는다() {
        givenAdmin();
        givenNormalizedImage();
        given(storage.put(any(), any(), any())).willThrow(new IllegalStateException("storage down"));

        assertThatThrownBy(() -> service.uploadBanner(1L, new byte[] {1}))
                .isInstanceOf(IllegalStateException.class);

        then(images).should().save(any());
        then(images).should(org.mockito.Mockito.never()).markUploaded(any(), any());
    }
}

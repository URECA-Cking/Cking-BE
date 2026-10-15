package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 관리자 템플릿 이미지 업로드(이슈 #593): 관리자 전용이고, Creator 이미지와 같은 정책으로 올리되 소유 기록은 남기지 않는다. */
class CreatorSpaceTemplateImageUploadServiceTest {

    private final MemberRepository members = mock(MemberRepository.class);
    private final ImageNormalizer normalizer = mock(ImageNormalizer.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final CreatorSpaceTemplateImageUploadService service = new CreatorSpaceTemplateImageUploadService(
            members, normalizer, storage,
            new CreatorSpaceProfileImageUrlResolver(new CreatorSpaceProfileImageProperties("https://cdn.example")));

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
        then(storage).should().put(key.capture(), org.mockito.ArgumentMatchers.eq(new byte[] {9}), org.mockito.ArgumentMatchers.eq("image/jpeg"));
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
    void Creator_이미지와_같은_해상도_정책으로_정규화한다() {
        givenAdmin();
        givenNormalizedImage();

        service.uploadBanner(1L, new byte[] {1});
        service.uploadProfile(1L, new byte[] {1});

        ArgumentCaptor<ImagePolicy> policy = ArgumentCaptor.forClass(ImagePolicy.class);
        then(normalizer).should(org.mockito.Mockito.times(2)).normalize(any(), policy.capture());
        assertThat(policy.getAllValues()).containsExactly(new ImagePolicy(600, 200, 2048), new ImagePolicy(200, 200, 2048));
    }

    @Test
    void 관리자가_아니면_FORBIDDEN이고_정규화하거나_저장하지_않는다() {
        given(members.findById(2L)).willReturn(Optional.of(new Member("일반사용자", null, null, MemberRole.USER)));

        assertThatThrownBy(() -> service.uploadBanner(2L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        then(normalizer).shouldHaveNoInteractions();
        then(storage).shouldHaveNoInteractions();
    }

    @Test
    void 없는_회원은_RESOURCE_NOT_FOUND다() {
        given(members.findById(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadProfile(3L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        then(storage).shouldHaveNoInteractions();
    }

    @Test
    void 이미지_검증에_실패하면_템플릿_이미지_오류_코드로_거절하고_저장하지_않는다() {
        givenAdmin();
        given(normalizer.normalize(any(), any())).willThrow(new InvalidImageException());

        assertThatThrownBy(() -> service.uploadBanner(1L, new byte[] {1}))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);

        then(storage).shouldHaveNoInteractions();
    }
}

package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import org.junit.jupiter.api.Test;

/** 템플릿 저장이 업로드한 템플릿 이미지를 고정하는 규칙(이슈 #598). */
class CreatorSpaceTemplateImagePinnerTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final String PROFILE_KEY = "creator-space-profile-images/template/profile/0a1b2c3d-0000-4000-8000-000000000001.jpg";
    private static final String BANNER_KEY = "creator-space-profile-images/template/banner/0a1b2c3d-0000-4000-8000-000000000002.jpg";

    private final CreatorSpaceTemplateImageRepository images = mock(CreatorSpaceTemplateImageRepository.class);
    private final CreatorSpaceTemplateImagePinner pinner = new CreatorSpaceTemplateImagePinner(images, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void URL에_들어_있는_프로필과_배너_key를_각각_고정한다() {
        given(images.pin(any(), any())).willReturn(1);

        pinner.pin("https://cdn.example/" + PROFILE_KEY, "https://cdn.example/" + BANNER_KEY);

        verify(images).pin(PROFILE_KEY, NOW);
        verify(images).pin(BANNER_KEY, NOW);
    }

    @Test
    void CDN_주소나_쿼리가_달라도_URL_안의_key를_찾아_고정한다() {
        given(images.pin(any(), any())).willReturn(1);

        pinner.pin("https://moved-cdn.example/assets/" + PROFILE_KEY + "?v=2", "https://img/default.png");

        verify(images).pin(PROFILE_KEY, NOW);
    }

    @Test
    void 같은_key가_두_URL에_있어도_한_번만_고정한다() {
        given(images.pin(any(), any())).willReturn(1);

        pinner.pin("https://cdn.example/" + BANNER_KEY, "https://other.example/" + BANNER_KEY);

        verify(images, times(1)).pin(eq(BANNER_KEY), any());
    }

    @Test
    void 템플릿_이미지가_아닌_URL은_기록을_건드리지_않는다() {
        pinner.pin("https://img/profile.png", "https://cdn.example/creator-space-profile-images/42/banner/uuid.jpg");

        verifyNoInteractions(images);
    }

    @Test
    void null_URL은_무시한다() {
        pinner.pin(null, "https://img/banner.png");

        verifyNoInteractions(images);
    }

    @Test
    void 고정에_실패했지만_기록이_없으면_추적하지_않는_URL이므로_허용한다() {
        given(images.pin(BANNER_KEY, NOW)).willReturn(0);
        given(images.existsByObjectKey(BANNER_KEY)).willReturn(false);

        pinner.pin("https://img/profile.png", "https://cdn.example/" + BANNER_KEY);

        verify(images).existsByObjectKey(BANNER_KEY);
    }

    @Test
    void 고정에_실패했고_기록이_있으면_정리되었거나_정리_중이므로_거절한다() {
        given(images.pin(BANNER_KEY, NOW)).willReturn(0);
        given(images.existsByObjectKey(BANNER_KEY)).willReturn(true);

        assertThatThrownBy(() -> pinner.pin("https://img/profile.png", "https://cdn.example/" + BANNER_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);
    }

    @Test
    void 하나라도_거절되면_예외로_끝나고_이미지_기록을_새로_만들지_않는다() {
        given(images.pin(PROFILE_KEY, NOW)).willReturn(1);
        given(images.pin(BANNER_KEY, NOW)).willReturn(0);
        given(images.existsByObjectKey(BANNER_KEY)).willReturn(true);

        assertThatThrownBy(() -> pinner.pin("https://cdn.example/" + PROFILE_KEY, "https://cdn.example/" + BANNER_KEY))
                .isInstanceOf(BusinessException.class);

        verify(images, never()).save(any());
    }
}

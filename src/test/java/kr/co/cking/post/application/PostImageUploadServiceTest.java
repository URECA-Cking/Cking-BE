package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.post.application.image.PostImageProcessor;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostImageStatus;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.ZoneOffset;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.creator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PostImageUploadServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long CREATOR_ID = 1L;
    private static final byte[] SOURCE = {1, 2, 3};
    private static final byte[] NORMALIZED = {9, 9};

    private final PostAuthorLookup authorLookup = mock(PostAuthorLookup.class);
    private final PostImageProcessor imageProcessor = mock(PostImageProcessor.class);
    private final CreatorPostImageRepository imageRepository = mock(CreatorPostImageRepository.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final PostImageUploadService service = new PostImageUploadService(
            authorLookup, imageProcessor, imageRepository, objectStorage, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 기록을_UPLOADING으로_먼저_남기고_put_후_UPLOADED로_바꾼다() {
        given(authorLookup.requireCreator(MEMBER_ID)).willReturn(creator(CREATOR_ID, MEMBER_ID));
        given(imageProcessor.process(SOURCE)).willReturn(normalized());

        String imageKey = service.upload(MEMBER_ID, SOURCE);

        assertThat(imageKey).matches("post-images/1/[0-9a-f-]{36}\\.jpg");
        ArgumentCaptor<CreatorPostImage> saved = ArgumentCaptor.forClass(CreatorPostImage.class);
        InOrder order = inOrder(imageRepository, objectStorage);
        order.verify(imageRepository).save(saved.capture());
        order.verify(objectStorage).put(imageKey, NORMALIZED, "image/jpeg");
        order.verify(imageRepository).markUploaded(imageKey, NOW);
        assertThat(saved.getValue().getObjectKey()).isEqualTo(imageKey);
        assertThat(saved.getValue().getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo(PostImageStatus.UPLOADING);
        assertThat(saved.getValue().getPostId()).isNull();
    }

    @Test
    void Creator가_아니면_이미지를_처리하지_않는다() {
        given(authorLookup.requireCreator(MEMBER_ID)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.upload(MEMBER_ID, SOURCE)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(imageProcessor, imageRepository, objectStorage);
    }

    @Test
    void 잘못된_이미지는_기록과_저장소에_남기지_않는다() {
        given(authorLookup.requireCreator(MEMBER_ID)).willReturn(creator(CREATOR_ID, MEMBER_ID));
        given(imageProcessor.process(SOURCE)).willThrow(new BusinessException(PostErrorCode.INVALID_POST_IMAGE));

        assertThatThrownBy(() -> service.upload(MEMBER_ID, SOURCE)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(imageRepository, objectStorage);
    }

    @Test
    void put이_실패하면_UPLOADING_기록이_남아_정리_대상이_된다() {
        given(authorLookup.requireCreator(MEMBER_ID)).willReturn(creator(CREATOR_ID, MEMBER_ID));
        given(imageProcessor.process(SOURCE)).willReturn(normalized());
        willThrow(new IllegalStateException("storage down"))
                .given(objectStorage).put(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.upload(MEMBER_ID, SOURCE)).isInstanceOf(IllegalStateException.class);

        verify(imageRepository).save(any(CreatorPostImage.class));
        verify(imageRepository, never()).markUploaded(anyString(), any());
    }

    private NormalizedImage normalized() {
        return new NormalizedImage(NORMALIZED, "source", "normalized", "JPEG_V1", 300, 300);
    }
}

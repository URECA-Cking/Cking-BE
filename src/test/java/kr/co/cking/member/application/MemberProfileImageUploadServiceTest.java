package kr.co.cking.member.application;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.application.image.MemberProfileImageProcessor;
import kr.co.cking.member.domain.MemberProfileImage;
import kr.co.cking.member.domain.MemberProfileImageStatus;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class MemberProfileImageUploadServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final byte[] SOURCE = {1, 2, 3};
    private static final byte[] NORMALIZED = {9, 9};

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorQueryService creatorQueryService = mock(CreatorQueryService.class);
    private final MemberProfileImageProcessor imageProcessor = mock(MemberProfileImageProcessor.class);
    private final MemberProfileImageRepository imageRepository = mock(MemberProfileImageRepository.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final MemberProfileImageUploadService service = new MemberProfileImageUploadService(
            memberRepository, creatorQueryService, imageProcessor, imageRepository, objectStorage,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 업로드_기록을_먼저_남기고_저장소_성공_뒤_연결_가능_상태로_바꾼다() {
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(imageProcessor.process(SOURCE)).willReturn(normalized());

        String imageKey = service.upload(MEMBER_ID, SOURCE);

        assertThat(imageKey).matches("member-profile-images/10/[0-9a-f-]{36}\\.jpg");
        ArgumentCaptor<MemberProfileImage> saved = ArgumentCaptor.forClass(MemberProfileImage.class);
        InOrder order = inOrder(imageRepository, objectStorage);
        order.verify(imageRepository).save(saved.capture());
        order.verify(objectStorage).put(imageKey, NORMALIZED, "image/jpeg");
        order.verify(imageRepository).markUploaded(imageKey, NOW);
        assertThat(saved.getValue().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo(MemberProfileImageStatus.UPLOADING);
    }

    @Test
    void 존재하지_않는_회원은_이미지를_처리하거나_저장하지_않는다() {
        given(memberRepository.existsById(MEMBER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.upload(MEMBER_ID, SOURCE))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(imageProcessor, imageRepository, objectStorage);
    }

    @Test
    void 저장소_실패_시_UPLOADING_기록을_남겨_정리_대상으로_둔다() {
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(imageProcessor.process(SOURCE)).willReturn(normalized());
        willThrow(new IllegalStateException("storage down"))
                .given(objectStorage).put(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.upload(MEMBER_ID, SOURCE)).isInstanceOf(IllegalStateException.class);

        verify(imageRepository).save(any(MemberProfileImage.class));
        verify(imageRepository, never()).markUploaded(anyString(), any());
    }

    @Test
    void Creator는_개인_프로필_이미지를_업로드할_수_없다() {
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(creatorQueryService.isCreatorMember(MEMBER_ID)).willReturn(true);

        assertThatThrownBy(() -> service.upload(MEMBER_ID, SOURCE))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.CREATOR_PROFILE_MANAGED_IN_SPACE);

        verifyNoInteractions(imageProcessor, imageRepository, objectStorage);
    }

    private NormalizedImage normalized() {
        return new NormalizedImage(NORMALIZED, "source", "normalized", "JPEG_V1", 300, 300);
    }
}

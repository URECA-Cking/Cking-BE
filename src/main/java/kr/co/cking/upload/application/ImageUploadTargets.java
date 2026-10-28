package kr.co.cking.upload.application;

import kr.co.cking.creator.application.CreatorSpaceProfileImageUploadService;
import kr.co.cking.event.application.EventBannerImageUploadService;
import kr.co.cking.member.application.MemberProfileImageUploadService;
import kr.co.cking.post.application.PostImageUploadService;
import kr.co.cking.upload.domain.ImageUploadPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 용도별 업로드 기록의 예약(권한 확인 포함)과 완료 표시를 기존 업로드 서비스에 맡긴다. */
@Component
@RequiredArgsConstructor
class ImageUploadTargets {

    private final MemberProfileImageUploadService memberProfile;
    private final CreatorSpaceProfileImageUploadService creatorSpace;
    private final EventBannerImageUploadService eventBanner;
    private final PostImageUploadService post;

    String reserve(ImageUploadPurpose purpose, Long memberId) {
        return switch (purpose) {
            case MEMBER_PROFILE -> memberProfile.reserve(memberId);
            case CREATOR_SPACE_PROFILE -> creatorSpace.reserve(memberId);
            case CREATOR_SPACE_BANNER -> creatorSpace.reserveBanner(memberId);
            case EVENT_BANNER -> eventBanner.reserve(memberId);
            case POST_IMAGE -> post.reserve(memberId);
        };
    }

    boolean markUploaded(ImageUploadPurpose purpose, String key) {
        return switch (purpose) {
            case MEMBER_PROFILE -> memberProfile.markUploaded(key);
            case CREATOR_SPACE_PROFILE, CREATOR_SPACE_BANNER -> creatorSpace.markUploaded(key);
            case EVENT_BANNER -> eventBanner.markUploaded(key);
            case POST_IMAGE -> post.markUploaded(key);
        };
    }
}

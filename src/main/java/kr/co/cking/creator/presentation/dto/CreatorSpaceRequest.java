package kr.co.cking.creator.presentation.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.creator.domain.CreatorSpaceSlugRule;

public final class CreatorSpaceRequest {

    private CreatorSpaceRequest() {
    }

    /** slug는 {@link ChangeSlug}로 따로 바꾼다. 필드 길이는 creator_space 컬럼과 같다. */
    public static final class UpdateProfile {
        @NotBlank @Size(max = 500)
        private String introText;
        @NotBlank @Size(max = 500)
        private String profileImageUrl;
        @NotBlank @Size(max = 500)
        private String bannerImageUrl;
        @Size(max = 200)
        private String profileImageKey;
        private boolean profileImageKeyProvided;
        /** 업로드한 배너 이미지 키. 생략하면 유지, 키면 교체, null이면 기본 배너로 복귀한다(이슈 #590). */
        @Size(max = 200)
        private String bannerImageKey;
        private boolean bannerImageKeyProvided;

        public String introText() { return introText; }
        public String profileImageUrl() { return profileImageUrl; }
        public String bannerImageUrl() { return bannerImageUrl; }
        public String profileImageKey() { return profileImageKey; }
        public boolean profileImageKeyProvided() { return profileImageKeyProvided; }
        public String bannerImageKey() { return bannerImageKey; }
        public boolean bannerImageKeyProvided() { return bannerImageKeyProvided; }

        @JsonSetter("introText")
        public void setIntroText(String introText) {
            this.introText = introText;
        }

        @JsonSetter("profileImageUrl")
        public void setProfileImageUrl(String profileImageUrl) {
            this.profileImageUrl = profileImageUrl;
        }

        @JsonSetter("bannerImageUrl")
        public void setBannerImageUrl(String bannerImageUrl) {
            this.bannerImageUrl = bannerImageUrl;
        }

        @JsonSetter("profileImageKey")
        public void setProfileImageKey(String profileImageKey) {
            this.profileImageKey = profileImageKey;
            this.profileImageKeyProvided = true;
        }

        @JsonSetter("bannerImageKey")
        public void setBannerImageKey(String bannerImageKey) {
            this.bannerImageKey = bannerImageKey;
            this.bannerImageKeyProvided = true;
        }
    }

    /**
     * 커스텀 slug 형식(3~30자)은 여기서 검증하지 않는다. 본인이 버린 이전 slug로 되돌리는 경우에는
     * 30자를 넘는 자동 slug일 수 있어서, 형식·예약어·중복은 Application이 새 slug일 때만 검증한다(이슈 #301).
     * 여기서는 컬럼 길이만 막는다.
     */
    public record ChangeSlug(
            @NotBlank @Size(max = CreatorSpaceSlugRule.MAX_SLUG_LENGTH) String slug
    ) {
    }
}

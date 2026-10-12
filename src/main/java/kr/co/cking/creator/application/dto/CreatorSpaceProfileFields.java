package kr.co.cking.creator.application.dto;

/** Creator 본인이 수정하는 Space 홈·프로필 필드다. */
public record CreatorSpaceProfileFields(
        String introText,
        String profileImageUrl,
        String bannerImageUrl,
        String profileImageKey,
        boolean profileImageKeyProvided
) {
    public CreatorSpaceProfileFields(String introText, String profileImageUrl, String bannerImageUrl) {
        this(introText, profileImageUrl, bannerImageUrl, null, false);
    }
}

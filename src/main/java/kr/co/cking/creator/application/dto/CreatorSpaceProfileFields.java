package kr.co.cking.creator.application.dto;

/**
 * Creator 본인이 수정하는 Space 홈·프로필 필드다.
 *
 * <p>{@code profileImageKey}·{@code bannerImageKey}는 업로드한 이미지 키이고, {@code *Provided}가 false면 요청에 없었다는 뜻이라
 * 현재 연결을 유지한다. provided인데 키가 null이면 기본 이미지로 복귀한다(이슈 #590에서 배너 키를 추가).
 */
public record CreatorSpaceProfileFields(
        String introText,
        String profileImageUrl,
        String bannerImageUrl,
        String profileImageKey,
        boolean profileImageKeyProvided,
        String bannerImageKey,
        boolean bannerImageKeyProvided
) {
    /** 프로필 사진 키만 다루는 기존 호출용. 배너 키는 요청에 없었던 것으로 본다. */
    public CreatorSpaceProfileFields(
            String introText, String profileImageUrl, String bannerImageUrl,
            String profileImageKey, boolean profileImageKeyProvided
    ) {
        this(introText, profileImageUrl, bannerImageUrl, profileImageKey, profileImageKeyProvided, null, false);
    }

    public CreatorSpaceProfileFields(String introText, String profileImageUrl, String bannerImageUrl) {
        this(introText, profileImageUrl, bannerImageUrl, null, false, null, false);
    }
}

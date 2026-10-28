package kr.co.cking.upload.domain;

/** 용도별 최소 크기는 기존 앱 정규화 정책과 같다. 긴 변 2048은 정규화 Lambda가 모든 용도에 같게 적용한다. */
public enum ImageUploadPurpose {
    MEMBER_PROFILE(200, 200),
    CREATOR_SPACE_PROFILE(200, 200),
    CREATOR_SPACE_BANNER(600, 200),
    EVENT_BANNER(600, 200),
    POST_IMAGE(200, 200);

    private final int minWidth;
    private final int minHeight;

    ImageUploadPurpose(int minWidth, int minHeight) {
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    public int minWidth() {
        return minWidth;
    }

    public int minHeight() {
        return minHeight;
    }
}

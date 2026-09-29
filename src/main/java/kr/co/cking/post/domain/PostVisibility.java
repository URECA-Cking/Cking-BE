package kr.co.cking.post.domain;

/** 게시글 공개 범위. */
public enum PostVisibility {
    /** 누구나 본문·이미지를 볼 수 있다. */
    PUBLIC,
    /** 팔로워와 작성한 Creator 본인만 본문·이미지를 볼 수 있다. 그 외에는 잠금 상태로만 노출한다. */
    FOLLOWERS
}

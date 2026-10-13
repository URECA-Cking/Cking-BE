package kr.co.cking.post.application.dto;

/**
 * 게시글의 좋아요·댓글 반응(이슈 #554).
 *
 * @param liked 조회자가 좋아요했는지. 비로그인이면 false
 */
public record PostEngagement(long likeCount, boolean liked, long commentCount) {

    /** 방금 만든 게시글처럼 아직 반응이 없는 상태. */
    public static final PostEngagement NONE = new PostEngagement(0, false, 0);
}

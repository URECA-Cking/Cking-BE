package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.PostLikeView;

/** 게시글 좋아요·취소 후의 상태. */
public record PostLikeResponse(Long postId, boolean liked, long likeCount, boolean missionAwarded) {

    public static PostLikeResponse from(PostLikeView view) {
        return new PostLikeResponse(view.postId(), view.liked(), view.likeCount(), view.missionAwarded());
    }
}

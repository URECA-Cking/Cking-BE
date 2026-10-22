package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.CreatorPostCommentOriginalView;

/** 필터링된 댓글의 원문. */
public record CreatorPostCommentOriginalResponse(Long commentId, String content) {

    public static CreatorPostCommentOriginalResponse from(CreatorPostCommentOriginalView view) {
        return new CreatorPostCommentOriginalResponse(view.commentId(), view.content());
    }
}

package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.CreatorPostCommentView;

import java.time.Instant;

/** @param writtenByCreator 게시글을 작성한 Creator 본인이 단 댓글이면 true */
public record CreatorPostCommentResponse(
        Long commentId,
        Long postId,
        Long authorMemberId,
        String authorName,
        boolean writtenByCreator,
        String content,
        Instant createdAt,
        Instant updatedAt
) {

    public static CreatorPostCommentResponse from(CreatorPostCommentView view) {
        return new CreatorPostCommentResponse(
                view.commentId(),
                view.postId(),
                view.authorMemberId(),
                view.authorName(),
                view.writtenByCreator(),
                view.content(),
                view.createdAt(),
                view.updatedAt());
    }
}

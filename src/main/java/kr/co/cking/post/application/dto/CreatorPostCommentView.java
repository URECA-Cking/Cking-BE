package kr.co.cking.post.application.dto;

import java.time.Instant;

/**
 * 게시글 댓글.
 *
 * @param writtenByCreator 게시글을 작성한 Creator 본인이 단 댓글이면 true
 */
public record CreatorPostCommentView(
        Long commentId,
        Long postId,
        Long authorMemberId,
        String authorName,
        boolean writtenByCreator,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}

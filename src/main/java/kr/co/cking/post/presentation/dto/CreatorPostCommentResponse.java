package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.CreatorPostCommentView;

import java.time.Instant;

/**
 * @param writtenByCreator 게시글을 작성한 Creator 본인이 단 댓글이면 true
 * @param content 필터링된 댓글(filtered)이면 null이다.
 * @param filtered 필터링되어 원문이 가려진 댓글이면 true. 작성자 본인에게는 항상 false다.
 * @param revealable filtered일 때 원문 보기를 눌러도 되는지. 개인정보로 막힌 댓글은 false다.
 */
public record CreatorPostCommentResponse(
        Long commentId,
        Long postId,
        Long authorMemberId,
        String authorName,
        boolean writtenByCreator,
        String content,
        boolean filtered,
        boolean revealable,
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
                view.filtered(),
                view.revealable(),
                view.createdAt(),
                view.updatedAt());
    }
}

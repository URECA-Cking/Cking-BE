package kr.co.cking.post.application.dto;

import java.time.Instant;

/**
 * 게시글 댓글.
 *
 * @param writtenByCreator 게시글을 작성한 Creator 본인이 단 댓글이면 true
 * @param content 필터링된 댓글(filtered)이면 null이다. 원문은 별도 요청으로만 내려준다.
 * @param filtered 필터가 BLOCK한 댓글이고 조회자가 작성자 본인이 아니다. 작성자 본인에게는 항상 false다.
 * @param revealable filtered일 때 원문 보기를 허용하는지. 개인정보 규칙으로 막힌 댓글은 false다.
 */
public record CreatorPostCommentView(
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
}

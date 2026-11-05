package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.AdminFilteredCommentView;
import kr.co.cking.post.domain.CommentFilterStatus;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 필터 차단 댓글 목록의 한 행.
 *
 * @param postPreview   게시글 본문 앞부분
 * @param creatorName   스페이스 Creator의 활동명
 * @param authorName    작성자 표시 이름. Creator 계정은 활동명, 그 밖의 회원은 닉네임이다
 * @param filterReasons 가장 최근 BLOCK 판정의 사유
 * @param filterStatus  판정 상태. DONE이 아니면 본문을 고쳐 재판정 전이라 사유가 이전 본문의 것이다
 * @param filteredAt    가장 최근 판정 시각
 */
public record AdminFilteredCommentResponse(
        Long commentId,
        Long postId,
        String postPreview,
        Long creatorId,
        String creatorName,
        Long authorMemberId,
        String authorName,
        String content,
        List<String> filterReasons,
        CommentFilterStatus filterStatus,
        Instant filteredAt
) {

    public static AdminFilteredCommentResponse from(AdminFilteredCommentView view) {
        return new AdminFilteredCommentResponse(
                view.commentId(),
                view.postId(),
                view.postPreview(),
                view.creatorId(),
                view.creatorName(),
                view.authorMemberId(),
                view.authorName(),
                view.content(),
                view.filterReasons(),
                view.filterStatus(),
                view.filteredAt());
    }
}

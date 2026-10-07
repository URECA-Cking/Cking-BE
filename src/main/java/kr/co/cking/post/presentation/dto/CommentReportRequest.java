package kr.co.cking.post.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPostCommentReport;

/**
 * 댓글 신고 요청. {@code detail}은 사유가 {@code OTHER}일 때만 필수이고 그 밖의 사유에서는 보내지 않는다(Service가 검증).
 */
public record CommentReportRequest(
        @NotNull CommentReportReason reason,
        @Size(max = CreatorPostCommentReport.MAX_DETAIL_LENGTH) String detail
) {
}

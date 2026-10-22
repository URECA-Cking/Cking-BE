package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.CreatorPostCommentReportService;
import kr.co.cking.post.presentation.dto.CommentReportRequest;
import kr.co.cking.post.presentation.dto.CommentReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Creator Space 게시글 댓글 신고 HTTP 요청을 처리한다(이슈 #485). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 댓글 신고", description = "게시글 댓글을 신고하는 API를 제공합니다.")
public class CreatorPostCommentReportController {

    private final CreatorPostCommentReportService reportService;

    @Operation(
            summary = "게시글 댓글 신고",
            description = "게시글을 볼 수 있는 로그인 사용자가 댓글을 신고합니다. 전체 공개 게시글은 팔로우하지 않아도 "
                    + "신고할 수 있고, 본인 댓글은 신고할 수 없습니다. 사유가 OTHER일 때만 설명(1~200자)이 필수입니다. "
                    + "같은 댓글을 다시 신고하면 새로 저장하지 않고 처음 접수된 신고를 그대로 돌려줍니다(멱등). "
                    + "신고해도 댓글의 노출 상태는 바뀌지 않으며, 신고자는 댓글 작성자와 Creator에게 노출되지 않습니다. "
                    + "신고자별 반복 신고는 일정 시간 안의 횟수로 제한합니다(429)."
    )
    @PostMapping("/api/creators/{creatorId}/posts/{postId}/comments/{commentId}/reports")
    public ResponseEntity<ApiResponse<CommentReportResponse>> report(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId,
            @PathVariable @Positive Long commentId,
            @Valid @RequestBody CommentReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(CommentReportResponse.from(
                reportService.report(memberId, creatorId, postId, commentId, request.reason(), request.detail()))));
    }
}

package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.AdminCommentReportCommandService;
import kr.co.cking.post.application.AdminCommentReportQueryService;
import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.presentation.dto.AdminCommentReportActionResponse;
import kr.co.cking.post.presentation.dto.AdminCommentReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 신고된 댓글 조회(이슈 #485)와 숨김·반려 조치(이슈 #499) HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 댓글 신고 관리", description = "신고된 게시글 댓글을 조회하고 숨김·반려로 처리하는 관리자 API를 제공합니다.")
public class AdminCommentReportController {

    private final AdminCommentReportQueryService queryService;
    private final AdminCommentReportCommandService commandService;

    @Operation(
            summary = "신고된 게시글 댓글 목록",
            description = "ADMIN 권한의 인증된 관리자가 신고된 댓글을 댓글별로 모아 가장 최근에 신고된 순서로 페이지 조회합니다. "
                    + "댓글 원문, 필터 차단 여부, 관리자 숨김 여부, 처리 상태, 신고 수, 접수 신고 수, 사유별 신고 수를 포함하며 "
                    + "신고자 정보는 포함하지 않습니다. status(PENDING·RESOLVED·REJECTED)로 거를 수 있습니다."
    )
    @GetMapping("/api/admin/comment-reports")
    public ApiResponse<PageResponse<AdminCommentReportResponse>> findReportedComments(
            @CurrentMemberId Long memberId,
            @RequestParam(required = false) CommentReportStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                queryService.findReportedComments(memberId, status, PageRequest.of(page, size))
                        .map(AdminCommentReportResponse::from)));
    }

    @Operation(
            summary = "신고된 댓글 숨김",
            description = "ADMIN 권한의 관리자가 신고된 댓글을 숨기고 그 댓글의 접수 신고를 모두 처리 완료로 바꿉니다. "
                    + "숨긴 댓글은 사용자 목록에서 가려지고 원문을 볼 수 없으며 되돌릴 수 없습니다. "
                    + "이미 숨긴 댓글을 다시 숨기면 그대로 성공하고, 접수 신고가 모두 반려된 댓글은 409입니다."
    )
    @PostMapping("/api/admin/comment-reports/{commentId}/hide")
    public ApiResponse<AdminCommentReportActionResponse> hide(
            @CurrentMemberId Long memberId,
            @PathVariable Long commentId
    ) {
        return ApiResponse.success(AdminCommentReportActionResponse.from(commandService.hide(memberId, commentId)));
    }

    @Operation(
            summary = "신고된 댓글 신고 반려",
            description = "ADMIN 권한의 관리자가 신고를 받아들이지 않고 그 댓글의 접수 신고를 모두 반려로 바꿉니다. "
                    + "댓글은 그대로 보입니다. 이미 반려한 댓글을 다시 반려하면 그대로 성공하고, 이미 숨긴 댓글은 409입니다."
    )
    @PostMapping("/api/admin/comment-reports/{commentId}/reject")
    public ApiResponse<AdminCommentReportActionResponse> reject(
            @CurrentMemberId Long memberId,
            @PathVariable Long commentId
    ) {
        return ApiResponse.success(AdminCommentReportActionResponse.from(commandService.reject(memberId, commentId)));
    }
}

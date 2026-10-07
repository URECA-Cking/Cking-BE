package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.AdminCommentReportQueryService;
import kr.co.cking.post.presentation.dto.AdminCommentReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 신고된 댓글 조회 HTTP 요청을 처리한다(이슈 #485). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 댓글 신고 관리", description = "신고된 게시글 댓글을 조회하는 관리자 API를 제공합니다.")
public class AdminCommentReportController {

    private final AdminCommentReportQueryService queryService;

    @Operation(
            summary = "신고된 게시글 댓글 목록",
            description = "ADMIN 권한의 인증된 관리자가 신고된 댓글을 댓글별로 모아 가장 최근에 신고된 순서로 페이지 조회합니다. "
                    + "댓글 원문, 필터 차단 여부, 신고 수, 사유별 신고 수를 포함하며 신고자 정보는 포함하지 않습니다."
    )
    @GetMapping("/api/admin/comment-reports")
    public ApiResponse<PageResponse<AdminCommentReportResponse>> findReportedComments(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                queryService.findReportedComments(memberId, PageRequest.of(page, size))
                        .map(AdminCommentReportResponse::from)));
    }
}

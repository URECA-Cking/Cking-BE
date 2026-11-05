package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.AdminFilteredCommentQueryService;
import kr.co.cking.post.presentation.dto.AdminFilteredCommentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 필터 차단 댓글 조회 HTTP 요청을 처리한다(이슈 #809). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 댓글 필터 관리", description = "필터가 차단한 게시글 댓글과 차단 사유를 조회하는 관리자 API를 제공합니다.")
public class AdminFilteredCommentController {

    private final AdminFilteredCommentQueryService queryService;

    @Operation(
            summary = "필터 차단 댓글 목록",
            description = "ADMIN 권한의 인증된 관리자가 필터가 차단한 댓글을 신고 여부와 상관없이 최근 판정 순으로 페이지 조회합니다. "
                    + "원문과 차단 사유를 포함합니다. 본문을 고쳐 재판정 전인 댓글의 사유는 이전 본문의 것입니다."
    )
    @GetMapping("/api/admin/filtered-comments")
    public ApiResponse<PageResponse<AdminFilteredCommentResponse>> findFilteredComments(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                queryService.findFilteredComments(memberId, PageRequest.of(page, size))
                        .map(AdminFilteredCommentResponse::from)));
    }
}

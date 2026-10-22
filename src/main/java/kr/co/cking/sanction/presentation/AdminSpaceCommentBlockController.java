package kr.co.cking.sanction.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.sanction.application.SpaceCommentBlockService;
import kr.co.cking.sanction.presentation.dto.AdminSpaceCommentBlockCreateRequest;
import kr.co.cking.sanction.presentation.dto.AdminSpaceCommentBlockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 스페이스 댓글 작성 차단 조회·설정·해제 HTTP 요청을 처리한다(이슈 #584). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "스페이스 댓글 차단 관리", description = "관리자가 모든 스페이스의 댓글 작성 차단을 조회하고 직접 걸거나 해제하는 API를 제공합니다.")
public class AdminSpaceCommentBlockController {

    private final SpaceCommentBlockService service;

    @Operation(
            summary = "스페이스 댓글 차단 목록",
            description = "ADMIN 권한의 관리자가 모든 스페이스의 차단을 시작 시각 최신순으로 페이지 조회합니다. creatorId로 한 스페이스만, "
                    + "memberId로 한 회원만, active=true로 지금 유효한 차단만 거를 수 있습니다. 크리에이터가 건 차단과 관리자가 건 차단을 모두 포함합니다."
    )
    @GetMapping("/api/admin/comment-blocks")
    public ApiResponse<PageResponse<AdminSpaceCommentBlockResponse>> findBlocks(
            @CurrentMemberId Long adminId,
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                service.findBlocksByAdmin(adminId, creatorId, memberId, active, PageRequest.of(page, size))
                        .map(AdminSpaceCommentBlockResponse::from)));
    }

    @Operation(
            summary = "스페이스 댓글 작성 차단",
            description = "ADMIN 권한의 관리자가 지정한 크리에이터의 스페이스에서 회원의 댓글 작성·수정을 지금부터 days일(1~365) 동안 "
                    + "차단합니다. 그 스페이스에서만 막고 경고 횟수에 세지 않습니다. 이미 유효한 차단이 있으면 새로 만들지 않고 기존 차단을 "
                    + "돌려줍니다. 차단된 회원에게 알림이 갑니다."
    )
    @PostMapping("/api/admin/comment-blocks")
    public ResponseEntity<ApiResponse<AdminSpaceCommentBlockResponse>> block(
            @CurrentMemberId Long adminId,
            @Valid @RequestBody AdminSpaceCommentBlockCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(AdminSpaceCommentBlockResponse.from(
                service.blockByAdmin(adminId, request.creatorId(), request.memberId(), request.days()))));
    }

    @Operation(
            summary = "스페이스 댓글 차단 해제",
            description = "ADMIN 권한의 관리자가 크리에이터나 관리자가 건 스페이스 차단을 지금 해제합니다. 이미 끝났거나 해제된 차단을 "
                    + "다시 해제하면 그대로 성공합니다. 유효한 차단을 풀면 차단됐던 회원에게 알림이 갑니다."
    )
    @PostMapping("/api/admin/comment-blocks/{sanctionId}/release")
    public ApiResponse<AdminSpaceCommentBlockResponse> release(
            @CurrentMemberId Long adminId,
            @PathVariable Long sanctionId
    ) {
        return ApiResponse.success(AdminSpaceCommentBlockResponse.from(service.releaseByAdmin(adminId, sanctionId)));
    }
}

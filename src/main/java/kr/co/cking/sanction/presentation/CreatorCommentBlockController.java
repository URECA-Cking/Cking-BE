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
import kr.co.cking.sanction.presentation.dto.CreatorCommentBlockCreateRequest;
import kr.co.cking.sanction.presentation.dto.CreatorCommentBlockResponse;
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

/** 크리에이터가 자기 스페이스의 댓글 작성 차단을 걸고 조회하고 푸는 HTTP 요청을 처리한다(이슈 #579). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "스페이스 댓글 차단", description = "크리에이터가 자기 스페이스에서 회원의 댓글 작성·수정을 차단하는 API를 제공합니다.")
public class CreatorCommentBlockController {

    private final SpaceCommentBlockService service;

    @Operation(
            summary = "스페이스 댓글 작성 차단",
            description = "Creator가 자기 스페이스에서 회원의 댓글 작성·수정을 지금부터 days일(1~365) 동안 차단합니다. "
                    + "그 스페이스에서만 막고 경고 횟수에 세지 않습니다. 이미 유효한 차단이 있으면 새로 만들지 않고 기존 차단을 "
                    + "돌려줍니다. 차단된 회원에게 알림이 갑니다."
    )
    @PostMapping("/api/creator/comment-blocks")
    public ResponseEntity<ApiResponse<CreatorCommentBlockResponse>> block(
            @CurrentMemberId Long creatorMemberId,
            @Valid @RequestBody CreatorCommentBlockCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(CreatorCommentBlockResponse.from(
                service.block(creatorMemberId, request.memberId(), request.days()))));
    }

    @Operation(
            summary = "스페이스 댓글 차단 목록",
            description = "Creator가 자기 스페이스에 건 차단을 시작 시각 최신순으로 페이지 조회합니다. "
                    + "active=true면 지금 유효한 차단만 돌려줍니다."
    )
    @GetMapping("/api/creator/comment-blocks")
    public ApiResponse<PageResponse<CreatorCommentBlockResponse>> findBlocks(
            @CurrentMemberId Long creatorMemberId,
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                service.findBlocks(creatorMemberId, active, PageRequest.of(page, size))
                        .map(CreatorCommentBlockResponse::from)));
    }

    @Operation(
            summary = "스페이스 댓글 차단 해제",
            description = "Creator가 자기가 건 차단을 지금 해제합니다. 이미 끝났거나 해제된 차단을 다시 해제하면 그대로 성공합니다. "
                    + "유효한 차단을 풀면 차단됐던 회원에게 알림이 갑니다."
    )
    @PostMapping("/api/creator/comment-blocks/{sanctionId}/release")
    public ApiResponse<CreatorCommentBlockResponse> release(
            @CurrentMemberId Long creatorMemberId,
            @PathVariable Long sanctionId
    ) {
        return ApiResponse.success(CreatorCommentBlockResponse.from(service.release(creatorMemberId, sanctionId)));
    }
}

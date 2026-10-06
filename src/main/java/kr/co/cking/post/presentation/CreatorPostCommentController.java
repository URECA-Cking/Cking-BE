package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.CreatorPostCommentService;
import kr.co.cking.post.presentation.dto.CreatorPostCommentOriginalResponse;
import kr.co.cking.post.presentation.dto.CreatorPostCommentRequest;
import kr.co.cking.post.presentation.dto.CreatorPostCommentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Creator Space 게시글 댓글 HTTP 요청을 처리한다(이슈 #341). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 댓글", description = "게시글 댓글 조회·작성·수정·삭제 API를 제공합니다.")
public class CreatorPostCommentController {

    private final CreatorPostCommentService commentService;

    @Operation(
            summary = "게시글 댓글 목록 조회",
            description = "작성 순으로 페이지 조회합니다. 게시글을 볼 수 있는 사람만 조회할 수 있으며, 팔로워 공개 게시글은 "
                    + "팔로워와 Creator 본인만 조회합니다. 로그인했다면 Access JWT를 함께 보냅니다."
    )
    @GetMapping("/api/creators/{creatorId}/posts/{postId}/comments")
    public ApiResponse<PageResponse<CreatorPostCommentResponse>> findByPost(
            @CurrentMemberId(required = false) Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                commentService.findByPost(creatorId, postId, memberId, PageRequest.of(page, size))
                        .map(CreatorPostCommentResponse::from)));
    }

    @Operation(
            summary = "필터링된 게시글 댓글 원문 조회",
            description = "필터링되어 목록에서 원문이 가려진 댓글의 원문을 돌려줍니다. 게시글을 볼 수 있는 사람이면 누구나 "
                    + "조회할 수 있으며, 개인정보로 막힌 댓글은 403(COMMENT_NOT_REVEALABLE)입니다. 필터링되지 않은 댓글과 "
                    + "작성자 본인의 댓글은 404입니다."
    )
    @GetMapping("/api/creators/{creatorId}/posts/{postId}/comments/{commentId}/original")
    public ApiResponse<CreatorPostCommentOriginalResponse> findOriginal(
            @CurrentMemberId(required = false) Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId,
            @PathVariable @Positive Long commentId
    ) {
        return ApiResponse.success(CreatorPostCommentOriginalResponse.from(
                commentService.findOriginal(creatorId, postId, commentId, memberId)));
    }

    @Operation(
            summary = "게시글 댓글 작성",
            description = "팔로워와 Creator 본인만 작성할 수 있습니다. 전체 공개 게시글도 팔로우해야 작성할 수 있습니다."
    )
    @PostMapping("/api/creators/{creatorId}/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CreatorPostCommentResponse>> create(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody CreatorPostCommentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(CreatorPostCommentResponse.from(
                commentService.create(memberId, creatorId, postId, request.content()))));
    }

    @Operation(
            summary = "게시글 댓글 수정",
            description = "본인 댓글만 수정할 수 있으며, 작성과 같이 팔로워이거나 Creator 본인이어야 합니다."
    )
    @PatchMapping("/api/creators/{creatorId}/posts/{postId}/comments/{commentId}")
    public ApiResponse<CreatorPostCommentResponse> update(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId,
            @PathVariable @Positive Long commentId,
            @Valid @RequestBody CreatorPostCommentRequest request
    ) {
        return ApiResponse.success(CreatorPostCommentResponse.from(
                commentService.update(memberId, creatorId, postId, commentId, request.content())));
    }

    @Operation(
            summary = "게시글 댓글 삭제",
            description = "댓글 작성자 본인과 게시글을 작성한 Creator 본인이 삭제할 수 있습니다. 팔로우를 끊어도 본인 댓글은 삭제할 수 있습니다."
    )
    @DeleteMapping("/api/creators/{creatorId}/posts/{postId}/comments/{commentId}")
    public ResponseEntity<Void> delete(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId,
            @PathVariable @Positive Long commentId
    ) {
        commentService.delete(memberId, creatorId, postId, commentId);
        return ResponseEntity.noContent().build();
    }
}

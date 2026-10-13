package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.PostLikeService;
import kr.co.cking.post.presentation.dto.PostLikeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creator Space 게시글 좋아요 HTTP 요청을 처리한다(이슈 #554). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 좋아요", description = "게시글 좋아요·취소 API를 제공합니다.")
public class PostLikeController {

    private final PostLikeService likeService;

    @Operation(
            summary = "게시글 좋아요",
            description = "로그인한 회원이 게시글에 좋아요합니다. 멱등하며 이미 좋아요한 상태에서 다시 요청해도 성공합니다. "
                    + "팔로워 공개 게시글은 팔로워와 Creator 본인만 좋아요할 수 있습니다(POST_FOLLOWERS_ONLY)."
    )
    @PostMapping("/api/creators/{creatorId}/posts/{postId}/likes")
    public ApiResponse<PostLikeResponse> like(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId
    ) {
        return ApiResponse.success(PostLikeResponse.from(likeService.like(memberId, creatorId, postId)));
    }

    @Operation(
            summary = "게시글 좋아요 취소",
            description = "자기 좋아요를 취소합니다. 멱등하며 좋아요하지 않은 상태에서 요청해도 성공합니다."
    )
    @DeleteMapping("/api/creators/{creatorId}/posts/{postId}/likes")
    public ApiResponse<PostLikeResponse> unlike(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId
    ) {
        return ApiResponse.success(PostLikeResponse.from(likeService.unlike(memberId, creatorId, postId)));
    }
}

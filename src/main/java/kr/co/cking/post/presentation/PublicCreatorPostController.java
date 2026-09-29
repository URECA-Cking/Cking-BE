package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.CreatorPostQueryService;
import kr.co.cking.post.presentation.dto.CreatorPostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Creator Space 게시글 공개 조회 HTTP 요청을 처리한다(이슈 #318). 로그인하지 않아도 조회할 수 있다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 공개 조회", description = "로그인 없이 Creator Space 게시글을 조회하는 API를 제공합니다.")
public class PublicCreatorPostController {

    private final CreatorPostQueryService postQueryService;

    @Operation(
            summary = "크리에이터 게시글 목록 조회",
            description = "최신 작성 순으로 페이지 조회합니다. 팔로워 공개 게시글은 팔로워와 Creator 본인이 아니면 "
                    + "본문·이미지 없이 잠금 상태(locked=true)로 반환합니다. 로그인했다면 Access JWT를 함께 보냅니다."
    )
    @GetMapping("/api/creators/{creatorId}/posts")
    public ApiResponse<PageResponse<CreatorPostResponse.Detail>> findByCreator(
            @CurrentMemberId(required = false) Long memberId,
            @PathVariable @Positive Long creatorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                postQueryService.findByCreator(creatorId, memberId, PageRequest.of(page, size))
                        .map(CreatorPostResponse.Detail::from)));
    }

    @Operation(
            summary = "크리에이터 게시글 상세 조회",
            description = "팔로워 공개 게시글은 팔로워와 Creator 본인만 조회할 수 있습니다. 로그인했다면 Access JWT를 함께 보냅니다."
    )
    @GetMapping("/api/creators/{creatorId}/posts/{postId}")
    public ApiResponse<CreatorPostResponse.Detail> findDetail(
            @CurrentMemberId(required = false) Long memberId,
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long postId
    ) {
        return ApiResponse.success(CreatorPostResponse.Detail.from(
                postQueryService.findDetail(creatorId, postId, memberId)));
    }
}

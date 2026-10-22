package kr.co.cking.follow.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.follow.presentation.dto.CreatorFollowResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 사용자의 크리에이터 팔로우 HTTP 요청을 처리한다(이슈 #328). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "크리에이터 팔로우", description = "인증된 사용자의 크리에이터 팔로우·언팔로우·조회 API를 제공합니다.")
public class CreatorFollowController {

    private final CreatorFollowService followService;
    private final CreatorFollowQueryService followQueryService;

    @Operation(
            summary = "크리에이터 팔로우",
            description = "멱등합니다. 이미 팔로우 중이어도 성공합니다. 본인 크리에이터는 팔로우할 수 없습니다."
    )
    @PutMapping("/api/creators/{creatorId}/follow")
    public ApiResponse<CreatorFollowResponse.Status> follow(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId
    ) {
        followService.follow(memberId, creatorId);
        return ApiResponse.success(new CreatorFollowResponse.Status(creatorId, true));
    }

    @Operation(summary = "크리에이터 언팔로우", description = "멱등합니다. 팔로우하지 않은 상태여도 성공합니다.")
    @DeleteMapping("/api/creators/{creatorId}/follow")
    public ApiResponse<CreatorFollowResponse.Status> unfollow(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId
    ) {
        followService.unfollow(memberId, creatorId);
        return ApiResponse.success(new CreatorFollowResponse.Status(creatorId, false));
    }

    @Operation(summary = "크리에이터 팔로우 여부 조회", description = "인증된 사용자가 해당 크리에이터를 팔로우 중인지 조회합니다.")
    @GetMapping("/api/creators/{creatorId}/follow")
    public ApiResponse<CreatorFollowResponse.Status> findStatus(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long creatorId
    ) {
        boolean following = followQueryService.findFollowStatus(memberId, creatorId);
        return ApiResponse.success(new CreatorFollowResponse.Status(creatorId, following));
    }

    @Operation(summary = "내 팔로우 목록 조회", description = "최근 팔로우한 순서로 페이지 조회합니다.")
    @GetMapping("/api/me/follows")
    public ApiResponse<PageResponse<CreatorFollowResponse.Followed>> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                followQueryService.findMine(memberId, PageRequest.of(page, size))
                        .map(CreatorFollowResponse.Followed::from)));
    }
}

package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorRecommendationQueryService;
import kr.co.cking.creator.application.RecommendationTrackingObserver;
import kr.co.cking.creator.presentation.dto.CreatorRecommendationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "개인화 Creator 추천", description = "관심 분야·팔로우 기반으로 저장된 Creator 추천 결과를 집계합니다.")
public class CreatorRecommendationController {

    private final CreatorRecommendationQueryService queryService;
    private final RecommendationTrackingObserver trackingObserver;

    /** 로그인한 회원의 개인화 추천 카드 목록을 조회하고, 노출 기록에 쓸 추천 요청 ID를 함께 내려준다. */
    @GetMapping("/api/me/creator-recommendations")
    @Operation(
            summary = "내 Creator 추천 조회",
            description = "회원이 고른 관심 분야와 팔로우한 Creator들의 현재 활성 추천을 저장 결과만으로 집계합니다. "
                    + "입력 신호에 따라 policyVersion이 HYBRID/INTEREST/FOLLOW_PERSONALIZED로 정해지고, "
                    + "현재 소개와 관련성 근거가 유효한 후보만 집계하며 결과가 비면 빈 목록을 반환합니다. "
                    + "각 항목의 followerCount는 카드 표시용 팔로워 수이며 점수·정렬에는 쓰이지 않습니다."
    )
    public ApiResponse<CreatorRecommendationResponse.Result> findMine(
            @CurrentMemberId Long memberId,
            @Parameter(description = "반환할 추천 Creator 수(1~20, 기본 10)")
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size
    ) {
        var view = queryService.findForMember(memberId, size);
        var requestId = trackingObserver.snapshot(memberId, view);
        return ApiResponse.success(CreatorRecommendationResponse.Result.from(view, requestId));
    }
}

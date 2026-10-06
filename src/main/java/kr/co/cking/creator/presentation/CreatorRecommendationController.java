package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorRecommendationQueryService;
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

    @GetMapping("/api/me/creator-recommendations")
    @Operation(
            summary = "내 Creator 추천 조회",
            description = "회원이 고른 관심 분야와 팔로우한 Creator들의 현재 활성 추천을 저장 결과만으로 집계합니다. "
                    + "입력 신호에 따라 policyVersion이 HYBRID/INTEREST/FOLLOW_PERSONALIZED로 정해집니다."
    )
    public ApiResponse<CreatorRecommendationResponse.Result> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size
    ) {
        return ApiResponse.success(CreatorRecommendationResponse.Result.from(
                queryService.findForMember(memberId, size)));
    }
}

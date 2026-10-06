package kr.co.cking.interest.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.RecommendationWriteAuthorizer;
import kr.co.cking.interest.application.InterestRecommendationResultService;
import kr.co.cking.interest.presentation.dto.InterestRecommendationRequest;
import kr.co.cking.interest.presentation.dto.InterestRecommendationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "관심 분야 추천", description = "Cking-LLM이 만든 관심 분야별 추천 후보 묶음을 적재합니다.")
public class InterestRecommendationController {

    private final InterestRecommendationResultService resultService;
    private final RecommendationWriteAuthorizer writeAuthorizer;

    @PutMapping("/api/admin/interests/{interestCode}/recommendations")
    @Operation(
            summary = "관심 분야 추천 결과 교체",
            description = "ADMIN 또는 추천 적재 API Key(X-Cking-Recommendation-Key)로 분야별 완결된 후보 묶음을 검증해 "
                    + "새 세대로 원자 교체합니다."
    )
    public ApiResponse<InterestRecommendationResponse.Stored> replace(
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 30) String interestCode,
            @Valid @RequestBody InterestRecommendationRequest.Replace request
    ) {
        writeAuthorizer.requireWriteAccess(authentication);
        return ApiResponse.success(InterestRecommendationResponse.Stored.from(
                resultService.replace(interestCode, request.toCommand())));
    }
}

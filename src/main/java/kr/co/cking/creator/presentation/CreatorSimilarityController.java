package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorSimilarityQueryService;
import kr.co.cking.creator.application.CreatorSimilarityResultService;
import kr.co.cking.creator.presentation.dto.CreatorSimilarityRequest;
import kr.co.cking.creator.presentation.dto.CreatorSimilarityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Creator 유사 추천", description = "저장된 단일 크리에이터 유사 추천 결과 적재·조회 API")
public class CreatorSimilarityController {

    private final CreatorSimilarityResultService resultService;
    private final CreatorSimilarityQueryService queryService;

    @PutMapping("/api/admin/creators/{creatorId}/similar")
    @Operation(
            summary = "유사 추천 결과 교체",
            description = "ADMIN이 Cking-LLM의 완결된 후보 묶음을 검증해 새 세대로 원자 교체합니다."
    )
    public ApiResponse<CreatorSimilarityResponse.Stored> replace(
            @CurrentMemberId Long adminId,
            @PathVariable @Positive Long creatorId,
            @Valid @RequestBody CreatorSimilarityRequest.Replace request
    ) {
        return ApiResponse.success(CreatorSimilarityResponse.Stored.from(
                resultService.replace(adminId, creatorId, request.toCommand())));
    }

    @GetMapping("/api/creators/{creatorId}/similar")
    @Operation(
            summary = "유사 크리에이터 조회",
            description = "인증이나 모델 API 호출 없이 현재 활성화된 저장 결과만 순위순으로 조회합니다."
    )
    public ApiResponse<CreatorSimilarityResponse.Result> findSimilar(
            @PathVariable @Positive Long creatorId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int size
    ) {
        return ApiResponse.success(CreatorSimilarityResponse.Result.from(
                queryService.findSimilar(creatorId, size)));
    }
}

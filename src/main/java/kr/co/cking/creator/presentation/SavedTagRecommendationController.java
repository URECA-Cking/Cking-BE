package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.SavedTagRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name="저장 태그 추천",description="현재 공개 확인 태그가 일치하는 후보만 반환하며 인기순으로 보충하지 않습니다.")
public class SavedTagRecommendationController {
    private final SavedTagRecommendationService service;
    @GetMapping("/api/me/creator-recommendations/by-tags")
    @Operation(summary="실제 저장 태그 OR 추천",description="source=INTEREST는 저장 관심 분야만, FOLLOW는 전체 팔로우의 확인 태그만 사용합니다. 기본 EXPLICIT는 명시 코드 OR입니다. 부족 후보를 보충하지 않습니다.")
    public ApiResponse<Map<String,Object>> find(@CurrentMemberId Long memberId,
            @RequestParam(required=false) String taxonomyVersion,
            @RequestParam(defaultValue="") List<String> parentCodes,
            @RequestParam(defaultValue="") List<String> topicCodes,
            @RequestParam(defaultValue="EXPLICIT") String source,
            @RequestParam(defaultValue="10") String size) {
        try {
            if (!"EXPLICIT".equals(source)) {
                if (taxonomyVersion != null || !parentCodes.isEmpty() || !topicCodes.isEmpty())
                    throw new kr.co.cking.common.exception.BusinessException(kr.co.cking.creator.domain.ContentTagErrorCode.INVALID_CONTENT_TAG);
                return ApiResponse.success(service.forMember(memberId, source, Integer.parseInt(size)));
            }
            return ApiResponse.success(service.find(memberId,taxonomyVersion,parentCodes,topicCodes,Integer.parseInt(size)));
        } catch (NumberFormatException error) {
            throw new kr.co.cking.common.exception.BusinessException(
                kr.co.cking.creator.domain.ContentTagErrorCode.INVALID_CONTENT_TAG);
        }
    }
    @GetMapping("/api/me/creator-recommendations/tag-catalog")
    @Operation(summary="승인 콘텐츠 태그 추천 카탈로그")
    public ApiResponse<Map<String,Object>> catalog() {
        return ApiResponse.success(service.catalog());
    }
    @GetMapping("/api/internal/content-tags/saved-recommendations/inputs")
    @Operation(summary="확인된 전체 태그 후보 snapshot",description="CONTENT_TAG_BATCH 전용 인증")
    public ApiResponse<Map<String,Object>> inputs() { return ApiResponse.success(service.inputs()); }
    @PutMapping("/api/internal/content-tags/saved-recommendations/rankings")
    @Operation(summary="전체 적격 태그 점수 snapshot 멱등 적재",description="CONTENT_TAG_BATCH 전용 인증")
    public ApiResponse<Map<String,Object>> apply(@RequestBody SavedTagRecommendationService.Delivery body) {
        return ApiResponse.success(service.apply(body));
    }
}

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
    @Operation(summary="실제 저장 태그 OR 추천",description="빈 선택/부족 후보를 허용합니다. PENDING은 순위 재생성이 필요합니다.")
    public ApiResponse<Map<String,Object>> find(@CurrentMemberId Long memberId,
            @RequestParam(required=false) String taxonomyVersion,
            @RequestParam(defaultValue="") List<String> parentCodes,
            @RequestParam(defaultValue="") List<String> topicCodes,
            @RequestParam(defaultValue="10") int size) {
        return ApiResponse.success(service.find(memberId,taxonomyVersion,parentCodes,topicCodes,size));
    }
    @GetMapping("/api/me/creator-recommendations/tag-catalog")
    @Operation(summary="승인 콘텐츠 태그 추천 카탈로그")
    public ApiResponse<Map<String,Object>> catalog() {
        var input=service.inputs();
        return ApiResponse.success(Map.of("taxonomyVersion",input.get("taxonomyVersion"),"taxonomyHash",input.get("taxonomyHash"),
                "tags",input.get("tags"),"maxParents",3,"maxTopics",9));
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

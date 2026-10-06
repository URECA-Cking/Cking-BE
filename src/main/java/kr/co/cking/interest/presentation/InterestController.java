package kr.co.cking.interest.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.interest.application.InterestQueryService;
import kr.co.cking.interest.presentation.dto.InterestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "관심 분야", description = "회원이 선택할 수 있는 관심 분야 분류체계를 조회합니다.")
public class InterestController {

    private final InterestQueryService queryService;

    @GetMapping("/api/interests")
    @Operation(
            summary = "선택 가능한 관심 분야 목록 조회",
            description = "활성 분류체계의 활성 분야를 displayOrder 순으로 반환합니다. 인증 없이 조회할 수 있습니다."
    )
    public ApiResponse<InterestResponse.Selectable> findSelectable() {
        return ApiResponse.success(InterestResponse.Selectable.from(queryService.findSelectable()));
    }
}

package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.winner.application.PublicWinnerQueryResult;
import kr.co.cking.winner.application.PublicWinnerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 외부 사용자에게 공개된 Winner 목록을 제공하는 Controller다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "공개 Winner", description = "공개된 INITIAL·REDRAW 추첨의 마스킹된 당첨자를 조회합니다.")
public class PublicWinnerController {

    private final PublicWinnerQueryService publicWinnerQueryService;

    /** Event의 공개 Winner를 개인정보 마스킹 상태로 조회한다. */
    @Operation(
            summary = "공개 Winner 조회",
            description = "PUBLIC 상태의 COMPLETED Drawing Winner만 반환하며 이름과 전화번호는 응답 단계에서 마스킹합니다."
    )
    @GetMapping("/api/events/{eventId}/winners")
    public ApiResponse<PublicWinnerQueryResult> getPublicWinners(@PathVariable @Positive Long eventId) {
        return ApiResponse.success(publicWinnerQueryService.getPublicWinners(eventId));
    }
}

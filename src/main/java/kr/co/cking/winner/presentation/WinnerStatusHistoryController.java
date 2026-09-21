package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.util.List;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.winner.application.WinnerStatusHistoryQueryService;
import kr.co.cking.winner.application.WinnerStatusHistoryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Winner 상태 변경 이력을 역할별 조회 권한에 따라 제공하는 Controller다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Winner 상태 이력", description = "Winner 상태 변경 전후와 감사 정보를 조회합니다.")
public class WinnerStatusHistoryController {

    private final WinnerStatusHistoryQueryService winnerStatusHistoryQueryService;

    /** USER 본인 또는 ADMIN이 Winner 상태 변경 이력을 시간순으로 조회한다. */
    @Operation(
            summary = "Winner 상태 이력 조회",
            description = "userId로 호출자 Member와 역할을 검증합니다. USER는 본인 Winner만, ADMIN은 모든 Winner의 "
                    + "변경 전후 상태·사유·변경 주체·변경 시각을 시간순으로 조회할 수 있습니다."
    )
    @GetMapping("/api/winners/{winnerId}/history")
    public ApiResponse<List<WinnerStatusHistoryResult>> getHistory(
            @PathVariable @Positive Long winnerId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(winnerStatusHistoryQueryService.getHistory(winnerId, userId));
    }
}

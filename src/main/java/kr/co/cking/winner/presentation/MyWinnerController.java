package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.util.List;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.winner.application.MyWinnerQueryService;
import kr.co.cking.winner.application.MyWinnerResult;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 당첨자 본인이 자신의 당첨 결과와 운영 상태를 조회하는 Controller다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "내 Winner", description = "당첨자 본인의 Winner 이력과 현재 운영 상태를 조회합니다.")
public class MyWinnerController {

    private final MyWinnerQueryService myWinnerQueryService;

    /** 요청한 userId의 INITIAL·REDRAW Winner와 각 WinnerManagement 현재 상태를 반환한다. */
    @Operation(
            summary = "내 당첨 결과 조회",
            description = "userId로 식별한 Member를 검증한 뒤 본인 소유 Winner만 반환합니다. "
                    + "Winner의 불변 데이터와 WinnerManagement의 현재 상태를 함께 조회합니다."
    )
    @GetMapping("/api/me/winners")
    public ApiResponse<List<MyWinnerResult>> getMyWinners(@RequestParam @Positive Long userId) {
        return ApiResponse.success(myWinnerQueryService.getMyWinners(userId));
    }
}

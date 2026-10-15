package kr.co.cking.ticket.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.ticket.application.MyTicketsSummaryService;
import kr.co.cking.ticket.application.dto.MyTicketsSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Ticket", description = "응모권 잔액 및 이력 조회 API")
public class MyTicketsController {

    private final MyTicketsSummaryService myTicketsSummaryService;

    /** 인증된 사용자의 공용 응모권과 팔로우한 Creator별 응모권을 한 번에 조회한다. */
    @Operation(
            summary = "내 응모권 요약 조회",
            description = "공용 응모권 잔액, 팔로우한 크리에이터별 응모권 잔액, 서버가 계산한 합계를 한 번에 조회합니다. "
                    + "잔액은 DB 기준의 표시용 값이며 응모 가능 여부의 근거가 아닙니다."
    )
    @GetMapping("/api/me/tickets")
    public ApiResponse<MyTicketsSummaryResponse> getSummary(@CurrentMemberId Long memberId) {
        return ApiResponse.success(myTicketsSummaryService.getSummary(memberId));
    }
}

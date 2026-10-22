package kr.co.cking.ticket.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.ticket.application.CommonTicketQueryService;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerPage;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code TicketQueryController}와 동일 계약의 공용 응모권 조회 API(이슈 #219). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Ticket", description = "응모권 잔액 및 이력 조회 API")
public class CommonTicketQueryController {

    private final CommonTicketQueryService commonTicketQueryService;

    /** 인증된 사용자의 공용 응모권 잔액을 조회한다. */
    @Operation(
            summary = "공용 응모권 잔액 조회",
            description = "크리에이터에 묶이지 않는 사용자의 현재 공용 응모권 잔액을 조회합니다."
    )
    @GetMapping("/api/tickets/common")
    public ApiResponse<CommonTicketBalanceResponse> getBalance(@CurrentMemberId Long memberId) {
        return ApiResponse.success(commonTicketQueryService.getBalance(memberId));
    }

    /** 인증된 사용자의 공용 응모권 이력을 조회한다. */
    @Operation(
            summary = "공용 응모권 이력 조회",
            description = "사용자의 공용 응모권 적립·사용 이력을 cursor 기반으로 조회합니다. "
                    + "size의 기본값은 20이며 1~100 범위입니다."
    )
    @GetMapping("/api/tickets/common/history")
    public ApiResponse<CommonTicketLedgerPage> getHistory(@CurrentMemberId Long memberId,
                                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                          @RequestParam(required = false) String cursor) {
        return ApiResponse.success(commonTicketQueryService.getLedger(memberId, size, cursor));
    }
}

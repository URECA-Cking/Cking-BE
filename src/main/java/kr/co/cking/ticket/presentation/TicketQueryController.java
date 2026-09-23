package kr.co.cking.ticket.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.ticket.application.TicketQueryService;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketLedgerPage;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Ticket", description = "응모권 잔액 및 이력 조회 API")
public class TicketQueryController {

    private final TicketQueryService ticketQueryService;

    @Operation(
            summary = "응모권 잔액 조회",
            description = "크리에이터별 사용자의 현재 응모권 잔액을 조회합니다."
    )
    /** 인증된 사용자의 Creator별 응모권 잔액을 조회한다. */
    @GetMapping("/api/creators/{creatorId}/tickets")
    public ApiResponse<TicketBalanceResponse> getBalance(@PathVariable Long creatorId, @CurrentMemberId Long memberId) {
        return ApiResponse.success(ticketQueryService.getBalance(creatorId, memberId));
    }

    @Operation(
            summary = "응모권 이력 조회",
            description = "크리에이터별 사용자의 응모권 적립·사용 이력을 cursor 기반으로 조회합니다. "
                    + "size의 기본값은 20이며 1~100 범위입니다."
    )
    /** 인증된 사용자의 Creator별 응모권 이력을 조회한다. */
    @GetMapping("/api/creators/{creatorId}/tickets/history")
    public ApiResponse<TicketLedgerPage> getHistory(@PathVariable Long creatorId,
                                                    @CurrentMemberId Long memberId,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                    @RequestParam(required = false) String cursor) {
        return ApiResponse.success(ticketQueryService.getLedger(creatorId, memberId, size, cursor));
    }
}

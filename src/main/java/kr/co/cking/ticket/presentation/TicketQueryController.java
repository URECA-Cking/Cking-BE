package kr.co.cking.ticket.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
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
public class TicketQueryController {

    private final TicketQueryService ticketQueryService;

    @GetMapping("/api/creators/{creatorId}/tickets")
    public ApiResponse<TicketBalanceResponse> getBalance(@PathVariable Long creatorId,
                                                         @RequestParam Long userId) {
        return ApiResponse.success(ticketQueryService.getBalance(creatorId, userId));
    }

    @GetMapping("/api/creators/{creatorId}/tickets/history")
    public ApiResponse<TicketLedgerPage> getHistory(@PathVariable Long creatorId,
                                                    @RequestParam Long userId,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                    @RequestParam(required = false) String cursor) {
        return ApiResponse.success(ticketQueryService.getLedger(creatorId, userId, size, cursor));
    }
}

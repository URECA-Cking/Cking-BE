package kr.co.cking.ticket.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.ticket.application.TicketAdminService;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.presentation.dto.TicketResyncRequest;

/** #207: 정합성 배치가 지속 불일치를 감지했을 때 운영자가 수동으로 재동기화하는 API. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Ticket Admin", description = "응모권 잔액 수동 보정 관리자 API")
public class TicketAdminController {

    private final TicketAdminService ticketAdminService;

    @Operation(
            summary = "응모권 잔액 수동 재동기화",
            description = "지정한 (memberId, creatorId)의 Redis 잔액을 DB 잔액 기준으로 재동기화합니다. "
                    + "미반영 SPEND·EARN 메시지가 남아 있으면 보정을 거부합니다."
    )
    @PostMapping("/api/admin/tickets/resync")
    /** 인증된 관리자가 대상 Member의 응모권 잔액을 DB 기준으로 재동기화한다. */
    public ApiResponse<TicketBalanceResponse> resync(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody TicketResyncRequest request
    ) {
        return ApiResponse.success(ticketAdminService.resync(
                memberId, request.memberId(), request.creatorId(), request.reason()));
    }
}

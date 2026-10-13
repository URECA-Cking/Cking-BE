package kr.co.cking.ticket.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.ticket.application.TicketAdminService;
import kr.co.cking.ticket.application.dto.AdminTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.presentation.dto.CommonTicketResyncRequest;
import kr.co.cking.ticket.presentation.dto.TicketResyncRequest;

/** 관리자 응모권 잔액 조회와 수동 재동기화 API를 제공한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "관리자 응모권", description = "응모권 잔액 관측과 수동 보정")
public class TicketAdminController {

    private final TicketAdminService ticketAdminService;

    /** 관리자가 대상 회원의 Redis·MySQL 응모권 잔액을 종류별로 관측한다. */
    @Operation(
            summary = "회원 응모권 잔액 비교 조회",
            description = "ADMIN 권한으로 지정한 회원의 Redis·MySQL 잔액을 조회합니다. "
                    + "CREATOR에는 creatorId가 필요하며 COMMON에는 사용할 수 없습니다. "
                    + "두 값은 서로 다른 시점의 관측값이므로 차이가 곧 장애를 뜻하지 않습니다."
    )
    @GetMapping("/api/admin/tickets/balance")
    public ApiResponse<AdminTicketBalanceResponse> getBalance(
            @CurrentMemberId Long adminId,
            @Parameter(description = "조회 대상 회원 ID", required = true)
            @RequestParam @Positive Long memberId,
            @Parameter(description = "CREATOR 또는 COMMON", required = true)
            @RequestParam CouponType couponType,
            @Parameter(description = "CREATOR 조회 시 필수, COMMON 조회 시 생략")
            @RequestParam(required = false) @Positive Long creatorId
    ) {
        return ApiResponse.success(ticketAdminService.getBalance(adminId, memberId, couponType, creatorId));
    }

    /** 인증된 관리자가 대상 Member의 응모권 잔액을 DB 기준으로 재동기화한다. */
    @Operation(
            summary = "응모권 잔액 수동 재동기화",
            description = "지정한 (memberId, creatorId)의 Redis 잔액을 DB 잔액 기준으로 재동기화합니다. "
                    + "미반영 SPEND·EARN 메시지가 남아 있으면 보정을 거부합니다."
    )
    @PostMapping("/api/admin/tickets/resync")
    public ApiResponse<TicketBalanceResponse> resync(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody TicketResyncRequest request
    ) {
        return ApiResponse.success(ticketAdminService.resync(
                memberId, request.memberId(), request.creatorId(), request.reason()));
    }

    /** 인증된 관리자가 대상 Member의 공용 응모권 잔액을 DB 기준으로 재동기화한다. */
    @Operation(
            summary = "공용 응모권 잔액 수동 재동기화",
            description = "지정한 memberId의 공용 응모권 Redis 잔액을 DB 잔액 기준으로 재동기화합니다. "
                    + "미반영 공용 SPEND·EARN 메시지가 남아 있으면 보정을 거부합니다."
    )
    @PostMapping("/api/admin/tickets/common/resync")
    public ApiResponse<CommonTicketBalanceResponse> resyncCommon(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CommonTicketResyncRequest request
    ) {
        return ApiResponse.success(ticketAdminService.resyncCommon(memberId, request.memberId(), request.reason()));
    }
}

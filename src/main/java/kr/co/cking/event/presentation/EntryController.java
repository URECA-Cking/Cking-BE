package kr.co.cking.event.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.event.application.EntryStatusQueryService;
import kr.co.cking.event.application.EventEntryQueryService;
import kr.co.cking.event.application.EventEntryService;
import kr.co.cking.event.application.dto.EntryCommand;
import kr.co.cking.event.application.dto.EntryHistoryPage;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.application.dto.EntryStatusResponse;
import kr.co.cking.event.presentation.dto.EntryRequest;
import kr.co.cking.event.presentation.dto.EntryResponse;
import kr.co.cking.ticket.domain.CouponType;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Entry", description = "이벤트 응모 및 내 응모 내역 조회 API")
public class EntryController {

    private final EventEntryService eventEntryService;
    private final EventEntryQueryService eventEntryQueryService;
    private final EntryStatusQueryService entryStatusQueryService;

    /** 인증된 사용자의 Event 응모 이력을 조회한다. */
    @Operation(
            summary = "내 응모 내역 조회",
            description = "사용자가 특정 이벤트에 응모한 내역을 appliedAt DESC, entryId DESC 순으로 cursor 기반 조회합니다. "
                    + "size의 기본값은 20이며 1~100 범위입니다."
    )
    @GetMapping("/api/events/{eventId}/entries/me")
    public ApiResponse<EntryHistoryPage> getMyEntries(
            @PathVariable @Positive Long eventId,
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success(eventEntryQueryService.getMyEntries(eventId, memberId, size, cursor));
    }

    @Operation(
            summary = "실시간 응모 현황 조회",
            description = "이벤트의 참여자 수·누적 사용 응모권 수를 조회합니다. userId를 전달하면 해당 사용자의 사용 "
                    + "응모권 수도 함께 반환합니다. 표시용 값이며 응모 승인·추첨의 근거가 아닙니다. "
                    + "요청 파라미터가 아니라 응답의 realtime 필드로 조회 출처를 알려줍니다 - "
                    + "true는 Redis 집계(응모 수락 기준), false는 DB 집계(Consumer 반영 기준)입니다."
    )
    @GetMapping("/api/events/{eventId}/entry-status")
    public ApiResponse<EntryStatusResponse> getEntryStatus(
            @PathVariable @Positive Long eventId,
            @RequestParam(required = false) @Positive Long userId
    ) {
        return ApiResponse.success(entryStatusQueryService.getStatus(eventId, userId));
    }

    /** 인증된 사용자의 응모권으로 Event 응모를 요청한다. */
    @Operation(
            summary = "이벤트 응모",
            description = "사용자의 응모권으로 이벤트에 응모합니다. requestId가 같은 재요청은 멱등하게 처리됩니다. "
                    + "couponType을 생략하면 CREATOR(해당 크리에이터 전용 응모권)로 처리하고, COMMON을 주면 "
                    + "크리에이터 무관 공용 응모권을 대신 씁니다."
    )
    @PostMapping("/api/events/{eventId}/entries")
    public ApiResponse<EntryResponse> apply(
            @PathVariable Long eventId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody EntryRequest request
    ) {
        CouponType couponType = request.couponType() != null ? request.couponType() : CouponType.CREATOR;
        EntryCommand command = new EntryCommand(memberId, request.requestId(), request.ticketCount(), couponType);
        EntryOutcome outcome = eventEntryService.apply(eventId, command);
        return ApiResponse.of(outcome.code().name(), EntryResponse.accepted(outcome.requestId(), outcome.eventId()));
    }
}

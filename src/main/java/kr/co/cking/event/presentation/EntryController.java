package kr.co.cking.event.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.event.application.EventEntryQueryService;
import kr.co.cking.event.application.EventEntryService;
import kr.co.cking.event.application.dto.EntryCommand;
import kr.co.cking.event.application.dto.EntryHistoryPage;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.presentation.dto.EntryRequest;
import kr.co.cking.event.presentation.dto.EntryResponse;
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

    @Operation(
            summary = "내 응모 내역 조회",
            description = "사용자가 특정 이벤트에 응모한 내역을 appliedAt DESC, entryId DESC 순으로 cursor 기반 조회합니다. "
                    + "size의 기본값은 20이며 1~100 범위입니다."
    )
    @GetMapping("/api/events/{eventId}/entries/me")
    public ApiResponse<EntryHistoryPage> getMyEntries(
            @PathVariable @Positive Long eventId,
            @RequestParam @Positive Long userId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success(eventEntryQueryService.getMyEntries(eventId, userId, size, cursor));
    }

    @Operation(
            summary = "이벤트 응모",
            description = "사용자의 응모권으로 이벤트에 응모합니다. requestId가 같은 재요청은 멱등하게 처리됩니다."
    )
    @PostMapping("/api/events/{eventId}/entries")
    public ApiResponse<EntryResponse> apply(@PathVariable Long eventId, @Valid @RequestBody EntryRequest request) {
        EntryCommand command = new EntryCommand(request.userId(), request.requestId(), request.ticketCount());
        EntryOutcome outcome = eventEntryService.apply(eventId, command);
        return ApiResponse.of(outcome.code().name(), EntryResponse.accepted(outcome.requestId(), outcome.eventId()));
    }
}

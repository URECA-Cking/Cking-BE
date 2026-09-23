package kr.co.cking.event.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.event.application.CreatorEventService;
import kr.co.cking.event.application.EventReviewService;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.presentation.dto.EventManagementRequest;
import kr.co.cking.event.presentation.dto.EventManagementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import java.util.List;

/** Creator Event 관리와 관리자 심사 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping
@Validated
@Tag(name = "Event 관리", description = "Creator Event 운영과 관리자 심사 API")
public class EventManagementController {

    private final CreatorEventService creatorEventService;
    private final EventReviewService eventReviewService;

    /** Creator가 소유한 삭제되지 않은 Event 목록을 페이지로 반환한다. */
    @GetMapping("/api/creator/events")
    @Operation(summary = "내 Event 목록 조회", description = "인증된 Creator가 소유한 삭제되지 않은 Event를 페이지로 조회합니다.")
    public ApiResponse<EventManagementResponse.PageResult<EventManagementResponse.Item>> findMine(
            @CurrentMemberId Long memberId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Event> events = creatorEventService.findMine(memberId, PageRequest.of(page, size));
        List<EventManagementResponse.Item> items = events.stream().map(EventManagementResponse.Item::from).toList();
        return ApiResponse.success(EventManagementResponse.PageResult.from(events, items));
    }

    /** 관리자의 Event 승인 대기 요청 목록을 페이지로 반환한다. */
    @GetMapping("/api/admin/events/pending")
    @Operation(summary = "Event 승인 대기 목록", description = "ADMIN 권한의 인증된 관리자가 PENDING_APPROVAL Event를 페이지로 조회합니다.")
    public ApiResponse<EventManagementResponse.PageResult<EventManagementResponse.ApprovalItem>> findPending(
            @CurrentMemberId Long memberId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var requests = eventReviewService.findPending(memberId, PageRequest.of(page, size));
        var items = requests.stream().map(EventManagementResponse.ApprovalItem::from).toList();
        return ApiResponse.success(EventManagementResponse.PageResult.from(requests, items));
    }

    /** Creator 소유 Event의 내용을 수정한다. */
    @PatchMapping("/api/creator/events/{eventId}")
    @Operation(summary = "Event 수정", description = "인증된 Creator가 DRAFT 또는 REJECTED Event를 수정합니다.")
    public ApiResponse<EventManagementResponse.Result> update(@CurrentMemberId Long memberId, @PathVariable Long eventId,
            @Valid @RequestBody EventManagementRequest.Update request) {
        Event event = creatorEventService.update(new kr.co.cking.event.application.dto.UpdateEventCommand(
                memberId, eventId, request.title(), request.description(), request.startAt(), request.endAt(),
                request.winnerCount(), request.drawMethod(), request.prizeAlgorithmVersion(), request.prizeConfigs()));
        return ApiResponse.success(EventManagementResponse.Result.from(event));
    }

    /** Creator 소유 초안 Event를 논리 삭제한다. */
    @DeleteMapping("/api/creator/events/{eventId}")
    @Operation(summary = "Event 삭제", description = "인증된 Creator가 DRAFT 또는 REJECTED Event를 논리 삭제합니다.")
    public ResponseEntity<Void> delete(@CurrentMemberId Long memberId, @PathVariable Long eventId) {
        creatorEventService.delete(memberId, eventId);
        return ResponseEntity.noContent().build();
    }

    /** Creator의 Event 생성 요청을 멱등 명령으로 전달한다. */
    @PostMapping("/api/creator/events")
    @Operation(summary = "Event 생성", description = "인증된 Creator가 requestId를 멱등 키로 Event 초안을 생성합니다.")
    public ResponseEntity<ApiResponse<EventManagementResponse.Result>> create(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody EventManagementRequest.Create request
    ) {
        Event event = creatorEventService.create(new CreateEventCommand(
                memberId, request.requestId(), request.title(), request.description(), request.startAt(),
                request.endAt(), request.winnerCount(), request.drawMethod(), request.prizeAlgorithmVersion(),
                request.prizeConfigs()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(EventManagementResponse.Result.from(event)));
    }

    /** Creator Event의 새 승인 요청을 생성한다. */
    @PostMapping("/api/creator/events/{eventId}/approval-request")
    @Operation(summary = "Event 승인 요청", description = "인증된 Creator가 DRAFT Event의 승인 요청을 생성합니다.")
    public ApiResponse<EventManagementResponse.Result> requestApproval(
            @CurrentMemberId Long memberId, @PathVariable Long eventId) {
        creatorEventService.requestApproval(memberId, eventId);
        return ApiResponse.success(new EventManagementResponse.Result(eventId,
                kr.co.cking.event.domain.EventStatus.PENDING_APPROVAL));
    }

    /** 관리자가 승인 대기 Event를 SCHEDULED 상태로 승인한다. */
    @PostMapping("/api/admin/events/{eventId}/approve")
    @Operation(summary = "Event 승인", description = "ADMIN 권한의 인증된 관리자가 PENDING_APPROVAL Event를 SCHEDULED로 전이합니다.")
    public ApiResponse<EventManagementResponse.Result> approve(@PathVariable Long eventId,
            @CurrentMemberId Long memberId) {
        eventReviewService.approve(memberId, eventId);
        return ApiResponse.success(new EventManagementResponse.Result(eventId,
                kr.co.cking.event.domain.EventStatus.SCHEDULED));
    }

    /** 관리자가 승인 대기 Event를 거절하고 사유를 이력에 기록한다. */
    @PostMapping("/api/admin/events/{eventId}/reject")
    @Operation(summary = "Event 거절", description = "ADMIN 권한의 인증된 관리자가 거절 사유를 기록하고 Event를 REJECTED로 전이합니다.")
    public ApiResponse<EventManagementResponse.Result> reject(@PathVariable Long eventId,
            @CurrentMemberId Long memberId, @Valid @RequestBody EventManagementRequest.Reject request) {
        eventReviewService.reject(memberId, eventId, request.rejectReason());
        return ApiResponse.success(new EventManagementResponse.Result(eventId,
                kr.co.cking.event.domain.EventStatus.REJECTED));
    }
}

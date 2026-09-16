package kr.co.cking.event.presentation;

import jakarta.validation.Valid;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.List;

/** Creator Event 관리와 관리자 심사 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping
public class EventManagementController {

    private final CreatorEventService creatorEventService;
    private final EventReviewService eventReviewService;

    /** Creator가 소유한 삭제되지 않은 Event 목록을 페이지로 반환한다. */
    @GetMapping("/api/creator/events")
    public ApiResponse<EventManagementResponse.PageResult<EventManagementResponse.Item>> findMine(
            @RequestParam Long userId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Event> events = creatorEventService.findMine(userId, PageRequest.of(page, size));
        List<EventManagementResponse.Item> items = events.stream().map(EventManagementResponse.Item::from).toList();
        return ApiResponse.success(EventManagementResponse.PageResult.from(events, items));
    }

    /** 관리자의 Event 승인 대기 요청 목록을 페이지로 반환한다. */
    @GetMapping("/api/admin/events/pending")
    public ApiResponse<EventManagementResponse.PageResult<EventManagementResponse.ApprovalItem>> findPending(
            @RequestParam Long userId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var requests = eventReviewService.findPending(userId, PageRequest.of(page, size));
        var items = requests.stream().map(EventManagementResponse.ApprovalItem::from).toList();
        return ApiResponse.success(EventManagementResponse.PageResult.from(requests, items));
    }

    /** Creator 소유 Event의 내용을 수정한다. */
    @PatchMapping("/api/creator/events/{eventId}")
    public ApiResponse<EventManagementResponse.Result> update(@PathVariable Long eventId,
            @Valid @RequestBody EventManagementRequest.Update request) {
        Event event = creatorEventService.update(new kr.co.cking.event.application.dto.UpdateEventCommand(
                request.userId(), eventId, request.title(), request.description(), request.startAt(), request.endAt(),
                request.winnerCount(), request.drawMethod()));
        return ApiResponse.success(EventManagementResponse.Result.from(event));
    }

    /** Creator 소유 초안 Event를 논리 삭제한다. */
    @DeleteMapping("/api/creator/events/{eventId}")
    public ResponseEntity<Void> delete(@PathVariable Long eventId, @RequestParam Long userId) {
        creatorEventService.delete(userId, eventId);
        return ResponseEntity.noContent().build();
    }

    /** Creator의 Event 생성 요청을 멱등 명령으로 전달한다. */
    @PostMapping("/api/creator/events")
    public ResponseEntity<ApiResponse<EventManagementResponse.Result>> create(
            @Valid @RequestBody EventManagementRequest.Create request
    ) {
        Event event = creatorEventService.create(new CreateEventCommand(
                request.userId(), request.requestId(), request.title(), request.description(), request.startAt(),
                request.endAt(), request.winnerCount(), request.drawMethod()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(EventManagementResponse.Result.from(event)));
    }

    /** Creator Event의 새 승인 요청을 생성한다. */
    @PostMapping("/api/creator/events/{eventId}/approval-request")
    public ApiResponse<EventManagementResponse.Result> requestApproval(@PathVariable Long eventId,
            @Valid @RequestBody EventManagementRequest.Actor request) {
        creatorEventService.requestApproval(request.userId(), eventId);
        return ApiResponse.success(new EventManagementResponse.Result(eventId,
                kr.co.cking.event.domain.EventStatus.PENDING_APPROVAL));
    }

    /** 관리자가 승인 대기 Event를 SCHEDULED 상태로 승인한다. */
    @PostMapping("/api/admin/events/{eventId}/approve")
    public ApiResponse<EventManagementResponse.Result> approve(@PathVariable Long eventId,
            @Valid @RequestBody EventManagementRequest.Actor request) {
        eventReviewService.approve(request.userId(), eventId);
        return ApiResponse.success(new EventManagementResponse.Result(eventId,
                kr.co.cking.event.domain.EventStatus.SCHEDULED));
    }

    /** 관리자가 승인 대기 Event를 거절하고 사유를 이력에 기록한다. */
    @PostMapping("/api/admin/events/{eventId}/reject")
    public ApiResponse<EventManagementResponse.Result> reject(@PathVariable Long eventId,
            @Valid @RequestBody EventManagementRequest.Reject request) {
        eventReviewService.reject(request.userId(), eventId, request.rejectReason());
        return ApiResponse.success(new EventManagementResponse.Result(eventId,
                kr.co.cking.event.domain.EventStatus.REJECTED));
    }
}

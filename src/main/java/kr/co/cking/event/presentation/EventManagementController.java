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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creator Event 관리와 관리자 심사 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping
public class EventManagementController {

    private final CreatorEventService creatorEventService;
    private final EventReviewService eventReviewService;

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
}

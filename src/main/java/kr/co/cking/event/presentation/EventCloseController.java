package kr.co.cking.event.presentation;

import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.event.application.ManualEventCloseService;
import kr.co.cking.event.presentation.dto.EventCloseRequest;
import kr.co.cking.event.presentation.dto.EventCloseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 수동 마감 HTTP 요청을 권한·상태 검증 서비스로 전달한다. */
@RestController
@RequiredArgsConstructor
public class EventCloseController {

    private final ManualEventCloseService manualEventCloseService;

    @PostMapping("/api/events/{eventId}/close")
    public ResponseEntity<ApiResponse<EventCloseResponse>> close(
            @PathVariable Long eventId,
            @Valid @RequestBody EventCloseRequest request
    ) {
        EventCloseResponse response = EventCloseResponse.from(manualEventCloseService.close(request.userId(), eventId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }
}

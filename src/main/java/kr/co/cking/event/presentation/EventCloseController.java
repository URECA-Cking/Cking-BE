package kr.co.cking.event.presentation;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Event Closing", description = "이벤트 수동 마감 및 마감 상태 조회 API")
public class EventCloseController {

    private final ManualEventCloseService manualEventCloseService;

    /** 검증된 수동 마감 요청을 처리하고 현재 마감 상태만 202 응답으로 반환한다. */
    @Operation(
            summary = "이벤트 수동 마감 요청",
            description = "관리자 또는 이벤트 소유 크리에이터가 마감을 요청합니다. "
                    + "처리는 비동기로 진행되며 202 Accepted와 현재 상태(CLOSING 또는 CLOSED)를 반환합니다."
    )
    @PostMapping("/api/events/{eventId}/close")
    public ResponseEntity<ApiResponse<EventCloseResponse>> close(
            @PathVariable Long eventId,
            @Valid @RequestBody EventCloseRequest request
    ) {
        EventCloseResponse response = EventCloseResponse.from(manualEventCloseService.close(request.userId(), eventId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }
}

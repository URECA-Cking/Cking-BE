package kr.co.cking.drawing.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.drawing.application.InitialDrawingPreparationService;
import kr.co.cking.drawing.application.DrawingPublicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
public class DrawingAdminController {

    private final InitialDrawingPreparationService initialDrawingPreparationService;
    private final DrawingPublicationService drawingPublicationService;

    /** INITIAL Drawing 실행 전에 관리자·Event·Snapshot 조건을 검증해 준비 정보를 반환한다. */
    @PostMapping("/api/admin/events/{eventId}/drawings")
    public ApiResponse<InitialDrawingResponse> prepareInitialDrawing(
            @PathVariable @Positive Long eventId,
            @Valid @RequestBody InitialDrawingRequest request
    ) {
        return ApiResponse.success(InitialDrawingResponse.from(
                initialDrawingPreparationService.prepare(request.userId(), eventId)
        ));
    }

    /** 관리자의 요청으로 완료된 INITIAL Drawing을 공개하고 현재 공개 상태를 반환한다. */
    @PostMapping("/api/admin/drawings/{drawingId}/publish")
    public ApiResponse<DrawingPublicationResponse> publishDrawing(
            @PathVariable @Positive Long drawingId,
            @Valid @RequestBody DrawingPublishRequest request
    ) {
        return ApiResponse.success(DrawingPublicationResponse.from(
                drawingPublicationService.publish(drawingId, request.userId())
        ));
    }
}

package kr.co.cking.drawing.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.drawing.application.DrawingPublicationService;
import kr.co.cking.drawing.application.InitialDrawingPreparationService;
import kr.co.cking.drawing.domain.Drawing;
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

    @PostMapping("/api/admin/events/{eventId}/drawings")
    public ApiResponse<InitialDrawingResponse> prepareInitialDrawing(
            @PathVariable @Positive Long eventId,
            @Valid @RequestBody InitialDrawingRequest request
    ) {
        return ApiResponse.success(InitialDrawingResponse.from(
                initialDrawingPreparationService.prepare(request.userId(), eventId)
        ));
    }

    /** 완료된 추첨 결과를 공개한다. 이미 공개된 Drawing에 다시 요청해도 안전하다(멱등). */
    @PostMapping("/api/admin/drawings/{drawingId}/publish")
    public ApiResponse<DrawingPublishResponse> publish(
            @PathVariable @Positive Long drawingId,
            @Valid @RequestBody DrawingPublishRequest request
    ) {
        Drawing drawing = drawingPublicationService.publish(drawingId, request.userId());
        return ApiResponse.success(DrawingPublishResponse.from(drawing));
    }
}

package kr.co.cking.drawing.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.drawing.application.DrawingAdminQueryService;
import kr.co.cking.drawing.application.DrawingQueryResult;
import kr.co.cking.drawing.application.DrawingResultQuery;
import kr.co.cking.drawing.application.InitialDrawingExecutionService;
import kr.co.cking.drawing.application.DrawingPublicQueryService;
import kr.co.cking.drawing.application.PublicDrawingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
public class DrawingAdminController {

    private final InitialDrawingExecutionService initialDrawingExecutionService;
    private final DrawingAdminQueryService drawingAdminQueryService;
    private final DrawingPublicQueryService drawingPublicQueryService;

    @PostMapping("/api/admin/events/{eventId}/drawings")
    public ApiResponse<InitialDrawingResponse> executeInitialDrawing(
            @PathVariable @Positive Long eventId,
            @Valid @RequestBody InitialDrawingRequest request
    ) {
        return ApiResponse.success(InitialDrawingResponse.from(
                initialDrawingExecutionService.execute(request.userId(), eventId)
        ));
    }

    @GetMapping("/api/admin/drawings/{drawingId}")
    public ApiResponse<DrawingQueryResult> getDrawing(
            @PathVariable @Positive Long drawingId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(drawingAdminQueryService.getDrawing(drawingId, userId));
    }

    @GetMapping("/api/admin/drawings/{drawingId}/result")
    public ApiResponse<DrawingResultQuery> getDrawingResult(
            @PathVariable @Positive Long drawingId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(drawingAdminQueryService.getDrawingResult(drawingId, userId));
    }

    @GetMapping("/api/events/{eventId}/winners")
    public ApiResponse<PublicDrawingResult> getPublishedWinners(@PathVariable @Positive Long eventId) {
        return ApiResponse.success(drawingPublicQueryService.getPublishedWinners(eventId));
    }
}

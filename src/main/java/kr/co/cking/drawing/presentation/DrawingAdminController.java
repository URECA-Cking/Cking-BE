package kr.co.cking.drawing.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.drawing.application.DrawingAdminQueryService;
import kr.co.cking.drawing.application.DrawingQueryResult;
import kr.co.cking.drawing.application.DrawingResultQuery;
import kr.co.cking.drawing.application.DrawingVerificationResult;
import kr.co.cking.drawing.application.DrawingVerificationService;
import kr.co.cking.drawing.application.InitialDrawingExecutionService;
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
    private final DrawingVerificationService drawingVerificationService;

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

    @PostMapping("/api/admin/drawings/{drawingId}/verify")
    public ApiResponse<DrawingVerificationResult> verifyDrawing(
            @PathVariable @Positive Long drawingId,
            @Valid @RequestBody DrawingVerificationRequest request
    ) {
        return ApiResponse.success(drawingVerificationService.verify(drawingId, request.userId()));
    }

    @GetMapping("/api/admin/drawings/{drawingId}/verification-history")
    public ApiResponse<PageResponse<DrawingVerificationResult>> getVerificationHistory(
            @PathVariable @Positive Long drawingId,
            @RequestParam @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(drawingVerificationService.getHistory(
                drawingId, userId, page, size));
    }
}

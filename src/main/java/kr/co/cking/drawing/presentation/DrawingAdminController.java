package kr.co.cking.drawing.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.drawing.application.DrawingAdminQueryService;
import kr.co.cking.drawing.application.DrawingQueryResult;
import kr.co.cking.drawing.application.DrawingResultQuery;
import kr.co.cking.drawing.application.InitialDrawingExecutionService;
import kr.co.cking.drawing.application.PublicationService;
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
@Tag(name = "Drawing 관리자", description = "초기 추첨 실행·결과 조회·공개를 관리합니다.")
public class DrawingAdminController {

    private final PublicationService publicationService;
    private final InitialDrawingExecutionService initialDrawingExecutionService;
    private final DrawingAdminQueryService drawingAdminQueryService;

    /** 관리자 요청으로 INITIAL Drawing을 실행하고 완료된 추첨 결과 요약을 반환한다. */
    @Operation(
            summary = "INITIAL 추첨 실행",
            description = "CLOSED Event의 검증된 Snapshot으로 INITIAL 추첨을 실행하고 완료 결과를 반환합니다."
    )
    @PostMapping("/api/admin/events/{eventId}/drawings")
    public ApiResponse<InitialDrawingResponse> executeInitialDrawing(
            @PathVariable @Positive Long eventId,
            @Valid @RequestBody InitialDrawingRequest request
    ) {
        return ApiResponse.success(InitialDrawingResponse.from(
                initialDrawingExecutionService.execute(request.userId(), eventId)
        ));
    }

    /** 관리자의 요청으로 완료된 INITIAL Drawing을 공개하고 현재 공개 상태를 반환한다. */
    @Operation(
            summary = "INITIAL 추첨 결과 공개",
            description = "COMPLETED·PRIVATE INITIAL Drawing을 공개하고 Event를 PUBLISHED로 함께 전이합니다. "
                    + "이미 공개된 Drawing은 Event도 PUBLISHED인 경우 현재 상태를 반환합니다."
    )
    @PostMapping("/api/admin/drawings/{drawingId}/publish")
    public ApiResponse<DrawingPublicationResponse> publishDrawing(
            @PathVariable @Positive Long drawingId,
            @Valid @RequestBody DrawingPublishRequest request
    ) {
        return ApiResponse.success(DrawingPublicationResponse.from(
                publicationService.publish(drawingId, request.userId())
        ));
    }

    /** 관리자가 Drawing의 상태와 실행 정보를 조회한다. */
    @Operation(summary = "Drawing 상세 조회", description = "관리자가 Drawing의 실행·공개 상태와 확정 정보를 조회합니다.")
    @GetMapping("/api/admin/drawings/{drawingId}")
    public ApiResponse<DrawingQueryResult> getDrawing(
            @PathVariable @Positive Long drawingId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(drawingAdminQueryService.getDrawing(drawingId, userId));
    }

    /** 관리자가 완료된 Drawing의 당첨 결과를 순위대로 조회한다. */
    @Operation(summary = "Drawing 당첨 결과 조회", description = "관리자가 완료된 Drawing의 당첨자를 순위순으로 조회합니다.")
    @GetMapping("/api/admin/drawings/{drawingId}/result")
    public ApiResponse<DrawingResultQuery> getDrawingResult(
            @PathVariable @Positive Long drawingId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(drawingAdminQueryService.getDrawingResult(drawingId, userId));
    }
}

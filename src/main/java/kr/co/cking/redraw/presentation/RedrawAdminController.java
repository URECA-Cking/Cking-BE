package kr.co.cking.redraw.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.redraw.application.RedrawRequestCreateCommand;
import kr.co.cking.redraw.application.RedrawRequestCreateResult;
import kr.co.cking.redraw.application.RedrawRequestCreateService;
import kr.co.cking.redraw.application.RedrawRequestDetailQueryService;
import kr.co.cking.redraw.application.RedrawRequestDetailResult;
import kr.co.cking.redraw.application.RedrawRequestReviewResult;
import kr.co.cking.redraw.application.RedrawRequestReviewService;
import kr.co.cking.redraw.application.RedrawRequestExecutionResult;
import kr.co.cking.redraw.application.RedrawRequestExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 RedrawRequest 생성 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Redraw 관리자", description = "관리자의 재추첨 요청 생성·검토·실행을 관리합니다.")
public class RedrawAdminController {

    private final RedrawRequestCreateService redrawRequestCreateService;
    private final RedrawRequestDetailQueryService redrawRequestDetailQueryService;
    private final RedrawRequestReviewService redrawRequestReviewService;
    private final RedrawRequestExecutionService redrawRequestExecutionService;

    /** 관리자가 공개 Event의 미점유 결원을 서버 계산으로 확정한 RedrawRequest를 생성한다. */
    @Operation(
            summary = "RedrawRequest 생성",
            description = "관리자만 PUBLISHED Event의 DECLINED·DISQUALIFIED 미점유 결원으로 요청을 생성합니다. "
                    + "원본 INITIAL Drawing과 결원 수는 서버가 결정하며, 같은 idempotencyKey·본문 재시도는 기존 요청을 반환합니다."
    )
    @PostMapping("/api/admin/events/{eventId}/redraw-requests")
    public ResponseEntity<ApiResponse<RedrawRequestCreateResponse>> createRedrawRequest(
            @PathVariable @Positive Long eventId,
            @Valid @RequestBody RedrawRequestCreateRequest request
    ) {
        RedrawRequestCreateResult result = redrawRequestCreateService.create(new RedrawRequestCreateCommand(
                request.userId(), eventId, request.reason(), request.idempotencyKey()
        ));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(RedrawRequestCreateResponse.from(result)));
    }

    /** 관리자가 RedrawRequest의 고정 결원과 검토·실행 이력을 조회한다. */
    @Operation(
            summary = "RedrawRequest 상세 조회",
            description = "관리자가 재추첨 요청의 결원 Winner, 원본·실행 Drawing, 검토·실행 상태를 조회합니다. "
                    + "후보 부족으로 실행되지 않은 요청은 redrawDrawingId가 null입니다."
    )
    @GetMapping("/api/admin/redraw-requests/{redrawRequestId}")
    public ApiResponse<RedrawRequestDetailResult> getRedrawRequest(
            @PathVariable @Positive Long redrawRequestId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(redrawRequestDetailQueryService.getDetail(redrawRequestId, userId));
    }

    /** 관리자가 검토 대기 RedrawRequest를 승인하고 실행 대기 상태를 유지한다. */
    @Operation(
            summary = "RedrawRequest 승인",
            description = "관리자만 REQUESTED 재추첨 요청을 APPROVED로 전이합니다. 실행 상태는 PENDING으로 유지하며, "
                    + "동시 심사는 하나만 완료되고 나머지는 상태 오류로 거부됩니다."
    )
    @PostMapping("/api/admin/redraw-requests/{redrawRequestId}/approve")
    public ApiResponse<RedrawRequestReviewResponse> approveRedrawRequest(
            @PathVariable @Positive Long redrawRequestId,
            @Valid @RequestBody RedrawRequestApproveRequest request
    ) {
        RedrawRequestReviewResult result = redrawRequestReviewService.approve(request.userId(), redrawRequestId);
        return ApiResponse.success(RedrawRequestReviewResponse.from(result));
    }

    /** 관리자가 검토 대기 RedrawRequest를 거절하고 사유를 심사 이력에 기록한다. */
    @Operation(
            summary = "RedrawRequest 거절",
            description = "관리자만 REQUESTED 재추첨 요청을 REJECTED로 전이하고 필수 거절 사유와 심사 정보를 기록합니다. "
                    + "동시 심사는 하나만 완료되고 나머지는 상태 오류로 거부됩니다."
    )
    @PostMapping("/api/admin/redraw-requests/{redrawRequestId}/reject")
    public ApiResponse<RedrawRequestReviewResponse> rejectRedrawRequest(
            @PathVariable @Positive Long redrawRequestId,
            @Valid @RequestBody RedrawRequestRejectRequest request
    ) {
        RedrawRequestReviewResult result = redrawRequestReviewService.reject(
                request.userId(), redrawRequestId, request.rejectReason()
        );
        return ApiResponse.success(RedrawRequestReviewResponse.from(result));
    }

    /** 관리자가 승인된 요청을 실행해 시스템3의 REDRAW Drawing 생성을 시작한다. */
    @Operation(
            summary = "REDRAW 실행",
            description = "관리자만 APPROVED·PENDING 요청을 한 번 실행할 수 있습니다. 고정 결원과 실제 결원 행을 재검증한 뒤 "
                    + "시스템3 REDRAW 실행 서비스에 위임합니다. 실행 실패 시 보존된 Drawing ID와 실패 이력을 반환하며, "
                    + "해당 Drawing은 Drawing Retry API로 재실행할 수 있습니다."
    )
    @PostMapping("/api/admin/redraw-requests/{redrawRequestId}/execute")
    public ApiResponse<RedrawRequestExecutionResponse> executeRedrawRequest(
            @PathVariable @Positive Long redrawRequestId,
            @Valid @RequestBody RedrawRequestExecuteRequest request
    ) {
        RedrawRequestExecutionResult result = redrawRequestExecutionService.execute(request.userId(), redrawRequestId);
        return ApiResponse.success(RedrawRequestExecutionResponse.from(result));
    }
}

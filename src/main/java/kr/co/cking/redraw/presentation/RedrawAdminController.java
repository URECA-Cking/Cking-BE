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
}

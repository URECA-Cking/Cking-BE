package kr.co.cking.redraw.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.redraw.application.RedrawRequestCreateCommand;
import kr.co.cking.redraw.application.RedrawRequestCreateResult;
import kr.co.cking.redraw.application.RedrawRequestCreateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 RedrawRequest 생성 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Redraw 관리자", description = "관리자의 재추첨 요청 생성·검토·실행을 관리합니다.")
public class RedrawAdminController {

    private final RedrawRequestCreateService redrawRequestCreateService;

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
}

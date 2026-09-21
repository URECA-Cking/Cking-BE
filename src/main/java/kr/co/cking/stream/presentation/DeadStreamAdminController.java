package kr.co.cking.stream.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.stream.application.DeadStreamAdminService;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.presentation.dto.DeadStreamReplayRequest;
import kr.co.cking.stream.presentation.dto.DeadStreamResponse;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Dead Stream", description = "Dead Stream 메시지 조회 및 수동 replay 관리자 API")
public class DeadStreamAdminController {

    private final DeadStreamAdminService deadStreamAdminService;

    @Operation(
            summary = "Dead Stream 목록 조회",
            description = "관리자가 Dead Stream 메시지를 오래된 것부터 조회합니다. status는 UNRESOLVED(기본) 또는 RESOLVED입니다. "
                    + "원본 payload는 반환하지 않습니다."
    )
    @GetMapping("/api/admin/dead-streams")
    public ApiResponse<PageResponse<DeadStreamResponse>> list(
            @RequestParam @Positive Long userId,
            @RequestParam(defaultValue = "UNRESOLVED") DeadStreamResolutionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                deadStreamAdminService.list(userId, status, page, size).map(DeadStreamResponse::from)));
    }

    @Operation(
            summary = "Dead Stream 수동 replay",
            description = "보존된 원본 payload를 다시 적용하고 RESOLVED로 표시합니다. 이미 RESOLVED인 메시지는 "
                    + "다시 적용하지 않고 현재 상태를 반환합니다."
    )
    @PostMapping("/api/admin/dead-streams/{id}/replay")
    public ApiResponse<DeadStreamResponse> replay(
            @PathVariable @Positive Long id,
            @Valid @RequestBody DeadStreamReplayRequest request
    ) {
        return ApiResponse.success(DeadStreamResponse.from(deadStreamAdminService.replay(request.userId(), id)));
    }
}

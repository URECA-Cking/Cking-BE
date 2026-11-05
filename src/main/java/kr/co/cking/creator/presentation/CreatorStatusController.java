package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorStatusService;
import kr.co.cking.creator.domain.CreatorStatus;
import kr.co.cking.creator.presentation.dto.CreatorStatusRequest;
import kr.co.cking.creator.presentation.dto.CreatorStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
@Tag(name = "Creator 자격", description = "관리자 자격 관리와 Creator 본인 상태 조회")
public class CreatorStatusController {
    private final CreatorStatusService service;

    @GetMapping("/api/admin/creators")
    @Operation(summary = "Creator 자격 목록", description = "ADMIN이 자격 상태별 Creator를 페이지로 조회합니다.")
    public ApiResponse<CreatorStatusResponse.PageResult<CreatorStatusResponse.CreatorItem>> list(
            @CurrentMemberId Long adminId, @RequestParam(required = false) CreatorStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var creators = service.findAll(adminId, status, PageRequest.of(page, size));
        return ApiResponse.success(CreatorStatusResponse.PageResult.from(creators,
                creators.stream().map(CreatorStatusResponse.CreatorItem::from).toList()));
    }

    @GetMapping("/api/admin/creators/{creatorId}")
    @Operation(summary = "Creator 자격 운영 상세")
    public ApiResponse<CreatorStatusResponse.CreatorItem> detail(
            @CurrentMemberId Long adminId, @PathVariable Long creatorId) {
        return ApiResponse.success(CreatorStatusResponse.CreatorItem.from(service.findOne(adminId, creatorId)));
    }

    @GetMapping("/api/admin/creators/{creatorId}/status-history")
    @Operation(summary = "Creator 자격 변경 이력")
    public ApiResponse<CreatorStatusResponse.PageResult<CreatorStatusResponse.History>> history(
            @CurrentMemberId Long adminId, @PathVariable Long creatorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var histories = service.findHistory(adminId, creatorId, PageRequest.of(page, size));
        return ApiResponse.success(CreatorStatusResponse.PageResult.from(histories,
                histories.stream().map(CreatorStatusResponse.History::from).toList()));
    }

    @GetMapping("/api/creator/me/status")
    @Operation(summary = "내 Creator 자격 상태", description = "본인의 현재 자격 상태와 공개 안내만 반환합니다.")
    public ApiResponse<CreatorStatusResponse.Mine> mine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(CreatorStatusResponse.Mine.from(service.findMine(memberId)));
    }

    @PostMapping("/api/admin/creators/{creatorId}/suspend")
    @Operation(summary = "Creator 자격 정지", description = "같은 requestId와 본문을 재전송하면 기존 변경 결과를 반환합니다.")
    public ApiResponse<CreatorStatusResponse.Changed> suspend(@CurrentMemberId Long adminId,
            @PathVariable Long creatorId, @Valid @RequestBody CreatorStatusRequest request) {
        return change(adminId, creatorId, CreatorStatus.SUSPENDED, request);
    }

    @PostMapping("/api/admin/creators/{creatorId}/reactivate")
    @Operation(summary = "Creator 자격 정지 해제", description = "박탈된 Creator는 복구할 수 없습니다.")
    public ApiResponse<CreatorStatusResponse.Changed> reactivate(@CurrentMemberId Long adminId,
            @PathVariable Long creatorId, @Valid @RequestBody CreatorStatusRequest request) {
        return change(adminId, creatorId, CreatorStatus.ACTIVE, request);
    }

    @PostMapping("/api/admin/creators/{creatorId}/revoke")
    @Operation(summary = "Creator 자격 박탈", description = "박탈은 되돌릴 수 없습니다.")
    public ApiResponse<CreatorStatusResponse.Changed> revoke(@CurrentMemberId Long adminId,
            @PathVariable Long creatorId, @Valid @RequestBody CreatorStatusRequest request) {
        return change(adminId, creatorId, CreatorStatus.REVOKED, request);
    }

    private ApiResponse<CreatorStatusResponse.Changed> change(Long adminId, Long creatorId,
            CreatorStatus next, CreatorStatusRequest request) {
        return ApiResponse.success(CreatorStatusResponse.Changed.from(
                service.change(adminId, creatorId, next, request.toCommand())));
    }
}

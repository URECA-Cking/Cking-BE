package kr.co.cking.sanction.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.sanction.application.SanctionQueryService;
import kr.co.cking.sanction.application.SanctionService;
import kr.co.cking.sanction.presentation.dto.AdminSanctionCreateRequest;
import kr.co.cking.sanction.presentation.dto.AdminSanctionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 회원 제재 조회·수동 제한·해제 HTTP 요청을 처리한다(이슈 #569). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "회원 제재 관리", description = "댓글 작성 제한 같은 회원 제재를 조회하고 직접 걸거나 해제하는 관리자 API를 제공합니다.")
public class AdminSanctionController {

    private final SanctionQueryService queryService;
    private final SanctionService commandService;

    @Operation(
            summary = "제재 목록",
            description = "ADMIN 권한의 관리자가 제재를 시작 시각 최신순으로 페이지 조회합니다. memberId로 한 회원의 제재만, "
                    + "active=true로 지금 유효한 제한만 거를 수 있습니다. 자동 경고와 수동 제한을 모두 포함합니다."
    )
    @GetMapping("/api/admin/sanctions")
    public ApiResponse<PageResponse<AdminSanctionResponse>> findSanctions(
            @CurrentMemberId Long adminId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                queryService.findSanctions(adminId, memberId, active, PageRequest.of(page, size))
                        .map(AdminSanctionResponse::from)));
    }

    @Operation(
            summary = "댓글 작성 제한 수동 설정",
            description = "ADMIN 권한의 관리자가 회원에게 지금부터 days일(1~365) 동안 댓글 작성·수정을 제한합니다. "
                    + "경고 횟수에는 세지 않으며, 이미 제한 중이어도 새 제한을 따로 기록합니다."
    )
    @PostMapping("/api/admin/sanctions")
    public ResponseEntity<ApiResponse<AdminSanctionResponse>> create(
            @CurrentMemberId Long adminId,
            @Valid @RequestBody AdminSanctionCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(AdminSanctionResponse.from(
                commandService.createManual(adminId, request.memberId(), request.days(), request.reason()))));
    }

    @Operation(
            summary = "제재 해제",
            description = "ADMIN 권한의 관리자가 유효한 제한을 지금 해제합니다. 이미 끝났거나 해제된 제한을 다시 해제하면 "
                    + "그대로 성공합니다. 자동 경고는 해제해도 경고 횟수 집계에 남습니다."
    )
    @PostMapping("/api/admin/sanctions/{sanctionId}/release")
    public ApiResponse<AdminSanctionResponse> release(
            @CurrentMemberId Long adminId,
            @PathVariable Long sanctionId
    ) {
        return ApiResponse.success(AdminSanctionResponse.from(commandService.release(adminId, sanctionId)));
    }
}

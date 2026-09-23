package kr.co.cking.creator.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorApplicationService;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.presentation.dto.CreatorApplicationRequest;
import kr.co.cking.creator.presentation.dto.CreatorApplicationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping
@Tag(name = "Creator 신청", description = "Creator 신청과 신청 이력 조회 API")
public class CreatorApplicationController {

    private final CreatorApplicationService creatorApplicationService;

    /** 인증된 사용자의 Creator 신청을 처리한다. */
    @PostMapping("/api/creator/applications")
    @Operation(summary = "Creator 신청", description = "인증된 사용자의 Creator 신청을 생성하거나 기존 PENDING 신청을 반환합니다.")
    public ResponseEntity<ApiResponse<CreatorApplicationResponse.Result>> apply(@CurrentMemberId Long memberId) {
        CreatorApplicationService.ApplyResult result = creatorApplicationService.apply(memberId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(CreatorApplicationResponse.Result.from(result.application())));
    }

    /** 인증된 사용자의 Creator 신청 이력을 조회한다. */
    @GetMapping("/api/creator/applications/me")
    @Operation(summary = "내 Creator 신청 조회", description = "인증된 사용자의 Creator 신청 이력을 페이지로 조회합니다.")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorApplicationResponse.Mine>> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorApplication> applications = creatorApplicationService.findMine(memberId, PageRequest.of(page, size));
        List<CreatorApplicationResponse.Mine> items = applications.stream()
                .map(application -> new CreatorApplicationResponse.Mine(
                        application.getId(), application.getStatus(), application.getRequestedAt().toInstant(java.time.ZoneOffset.UTC),
                        application.getReviewedAt() == null ? null : application.getReviewedAt().toInstant(java.time.ZoneOffset.UTC), application.getRejectReason()))
                .toList();
        return ApiResponse.success(CreatorApplicationResponse.PageResult.from(applications, items));
    }

    /** 인증된 관리자가 Creator 신청 목록을 심사 순서대로 조회한다. */
    @GetMapping("/api/admin/creator-applications")
    @Operation(summary = "Creator 신청 관리자 목록", description = "ADMIN 권한의 인증된 관리자가 심사 대기와 이력 신청을 페이지로 조회합니다.")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorApplicationResponse.Admin>> findAllForAdmin(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorApplicationService.AdminApplication> applications = creatorApplicationService.findAllForAdmin(
                memberId, PageRequest.of(page, size));
        List<CreatorApplicationResponse.Admin> items = applications.stream()
                .map(application -> CreatorApplicationResponse.Admin.from(
                        application.application(), application.applicantName()))
                .toList();
        return ApiResponse.success(CreatorApplicationResponse.PageResult.from(applications, items));
    }

    /** 인증된 관리자가 대기 중인 Creator 신청을 승인한다. */
    @PostMapping("/api/admin/creator-applications/{applicationId}/approve")
    @Operation(summary = "Creator 신청 승인", description = "ADMIN 권한의 인증된 관리자가 PENDING 신청을 승인하고 기본 미션을 초기화합니다.")
    public ApiResponse<CreatorApplicationResponse.Result> approve(@PathVariable Long applicationId,
                                                                    @CurrentMemberId Long memberId) {
        return ApiResponse.success(CreatorApplicationResponse.Result.from(
                creatorApplicationService.approve(memberId, applicationId)));
    }

    /** 인증된 관리자가 거절 사유를 기록하며 Creator 신청을 거절한다. */
    @PostMapping("/api/admin/creator-applications/{applicationId}/reject")
    @Operation(summary = "Creator 신청 거절", description = "ADMIN 권한의 인증된 관리자가 거절 사유를 기록하고 PENDING 신청을 거절합니다.")
    public ApiResponse<CreatorApplicationResponse.Result> reject(
            @PathVariable Long applicationId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorApplicationRequest.Reject request
    ) {
        return ApiResponse.success(CreatorApplicationResponse.Result.from(
                creatorApplicationService.reject(memberId, applicationId, request.rejectReason())));
    }

}

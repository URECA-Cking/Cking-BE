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

    @PostMapping("/api/creator/applications")
    @Operation(summary = "Creator 신청", description = "인증된 사용자의 Creator 신청을 생성하거나 기존 PENDING 신청을 반환합니다.")
    /** 인증된 사용자의 Creator 신청을 처리한다. */
    public ResponseEntity<ApiResponse<CreatorApplicationResponse.Result>> apply(@CurrentMemberId Long memberId) {
        CreatorApplicationService.ApplyResult result = creatorApplicationService.apply(memberId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(CreatorApplicationResponse.Result.from(result.application())));
    }

    @GetMapping("/api/creator/applications/me")
    @Operation(summary = "내 Creator 신청 조회", description = "인증된 사용자의 Creator 신청 이력을 페이지로 조회합니다.")
    /** 인증된 사용자의 Creator 신청 이력을 조회한다. */
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

    @GetMapping("/api/admin/creator-applications")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorApplicationResponse.Admin>> findAllForAdmin(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorApplicationService.AdminApplication> applications = creatorApplicationService.findAllForAdmin(
                userId, PageRequest.of(page, size));
        List<CreatorApplicationResponse.Admin> items = applications.stream()
                .map(application -> CreatorApplicationResponse.Admin.from(
                        application.application(), application.applicantName()))
                .toList();
        return ApiResponse.success(CreatorApplicationResponse.PageResult.from(applications, items));
    }

    @PostMapping("/api/admin/creator-applications/{applicationId}/approve")
    public ApiResponse<CreatorApplicationResponse.Result> approve(
            @PathVariable Long applicationId,
            @Valid @RequestBody CreatorApplicationRequest.Review request
    ) {
        return ApiResponse.success(CreatorApplicationResponse.Result.from(
                creatorApplicationService.approve(request.userId(), applicationId)));
    }

    @PostMapping("/api/admin/creator-applications/{applicationId}/reject")
    public ApiResponse<CreatorApplicationResponse.Result> reject(
            @PathVariable Long applicationId,
            @Valid @RequestBody CreatorApplicationRequest.Reject request
    ) {
        return ApiResponse.success(CreatorApplicationResponse.Result.from(
                creatorApplicationService.reject(request.userId(), applicationId, request.rejectReason())));
    }

}

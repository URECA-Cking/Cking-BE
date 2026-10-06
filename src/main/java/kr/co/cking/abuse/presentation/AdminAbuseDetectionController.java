package kr.co.cking.abuse.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import kr.co.cking.abuse.application.AdminAbuseDetectionQueryService;
import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.presentation.dto.AbuseDetectionListItemResponse;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 Detection 목록 조회 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Abuse Detection", description = "비정상 행동 탐지 결과 조회 및 검토 관리자 API")
public class AdminAbuseDetectionController {

    private final AdminAbuseDetectionQueryService adminAbuseDetectionQueryService;

    /** 관리자가 조건과 기간으로 Detection 목록을 최신순 페이지 조회한다. */
    @Operation(
            summary = "Detection 목록 조회",
            description = "관리자가 회원·탐지 유형·검토 상태·탐지 기간으로 Detection을 조회합니다. "
                    + "전체 Evidence나 원본 요청 정보는 반환하지 않으며 detectedAt DESC, detectionId DESC 순으로 정렬합니다."
    )
    @GetMapping("/api/admin/abuse-detections")
    public ApiResponse<PageResponse<AbuseDetectionListItemResponse>> list(
            @CurrentMemberId Long memberId,
            @RequestParam(name = "memberId", required = false) @Positive Long targetMemberId,
            @RequestParam(required = false) AbuseType abuseType,
            @RequestParam(required = false) AbuseDetectionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant detectedAtFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant detectedAtTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        validatePeriod(detectedAtFrom, detectedAtTo);
        AbuseDetectionSearchCondition condition = new AbuseDetectionSearchCondition(
                targetMemberId, abuseType, status, detectedAtFrom, detectedAtTo);
        return ApiResponse.success(PageResponse.from(
                adminAbuseDetectionQueryService.list(memberId, condition, page, size)
                        .map(AbuseDetectionListItemResponse::from)));
    }

    /** 기간 시작이 종료보다 늦은 잘못된 목록 조건을 공통 입력 검증 오류로 변환한다. */
    private void validatePeriod(Instant detectedAtFrom, Instant detectedAtTo) {
        if (detectedAtFrom != null && detectedAtTo != null && detectedAtFrom.isAfter(detectedAtTo)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}

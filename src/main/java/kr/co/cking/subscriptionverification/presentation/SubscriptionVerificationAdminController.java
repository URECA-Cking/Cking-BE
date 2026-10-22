package kr.co.cking.subscriptionverification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationImageReuseQueryResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 구독 인증 이미지 재사용 탐지 결과를 조회하는 API다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "구독 인증 운영", description = "구독 인증 이미지 재사용 탐지 결과 운영 조회 API")
public class SubscriptionVerificationAdminController {

    private final SubscriptionVerificationQueryService queryService;

    /** 이미지·hash를 노출하지 않고 Verification 간 재사용 탐지 결과만 반환한다. */
    @GetMapping("/api/admin/subscription-verifications/{verificationId}/image-reuse")
    @Operation(
            summary = "구독 인증 이미지 재사용 탐지 조회",
            description = "정규화 이미지 SHA-256 exact match 탐지 결과만 조회하며 VLM 판정이나 보상 상태는 변경하지 않습니다."
    )
    public ApiResponse<SubscriptionVerificationImageReuseQueryResult> getImageReuse(
            @PathVariable @Positive Long verificationId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(queryService.getImageReuseForAdmin(memberId, verificationId));
    }
}

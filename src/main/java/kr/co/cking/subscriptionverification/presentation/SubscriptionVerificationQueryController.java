package kr.co.cking.subscriptionverification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationQueryResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 사용자가 자신의 구독 인증 처리 상태를 조회하는 API다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "구독 인증 상태", description = "내 YouTube 구독 인증 처리 상태 조회 API")
public class SubscriptionVerificationQueryController {

    private final SubscriptionVerificationQueryService queryService;

    @GetMapping("/api/subscription-verifications/{verificationId}")
    @Operation(summary = "내 구독 인증 상태 조회")
    public ApiResponse<SubscriptionVerificationQueryResult> getMine(
            @PathVariable @Positive Long verificationId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(queryService.getMine(memberId, verificationId));
    }

    @GetMapping("/api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest")
    @Operation(summary = "미션의 내 최신 구독 인증 조회")
    public ApiResponse<SubscriptionVerificationQueryResult> getLatestMine(
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long missionId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(queryService.getLatestMine(memberId, creatorId, missionId));
    }
}

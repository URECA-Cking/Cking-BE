package kr.co.cking.subscriptionverification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageProcessingLimiter;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationSubmissionCommand;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationAvailability;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationSubmissionResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationSubmissionService;
import kr.co.cking.subscriptionverification.presentation.dto.SubscriptionVerificationSubmissionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "구독 인증 제출", description = "YouTube 구독 인증 이미지 제출 API")
public class SubscriptionVerificationSubmissionController {

    private final SubscriptionVerificationSubmissionService submissionService;
    private final SubscriptionVerificationAvailability availability;
    private final ImageProcessingLimiter imageProcessingLimiter;

    @PostMapping(
            value = "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications",
            consumes = "multipart/form-data"
    )
    @Operation(summary = "구독 인증 이미지 제출", description = "이미지를 저장하고 비동기 검증 대기 상태를 생성합니다.")
    public ResponseEntity<ApiResponse<SubscriptionVerificationSubmissionResponse>> submit(
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long missionId,
            @RequestParam("requestId") UUID requestId,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @CurrentMemberId Long memberId
    ) {
        availability.requireSubmissionEnabled();
        SubscriptionVerificationSubmissionResult result = imageProcessingLimiter.admit(
                () -> submissionService.submit(
                        new SubscriptionVerificationSubmissionCommand(
                                memberId, creatorId, missionId, requestId, readBytes(image))));
        HttpStatus status = result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(
                SubscriptionVerificationSubmissionResponse.from(result.verification())));
    }

    private byte[] readBytes(MultipartFile image) {
        if (image == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return image.getBytes();
        } catch (IOException exception) {
            log.error("구독 인증 multipart 이미지를 읽지 못했습니다.", exception);
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
    }
}

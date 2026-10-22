package kr.co.cking.subscriptionverification.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 제출 기능 플래그를 HTTP 파일 읽기와 Application 처리 양쪽 경계에서 일관되게 검사한다. */
@Component
public class SubscriptionVerificationAvailability {

    private final boolean submissionEnabled;

    public SubscriptionVerificationAvailability(
            @Value("${cking.verification.youtube-subscription.submission-enabled:false}")
            boolean submissionEnabled
    ) {
        this.submissionEnabled = submissionEnabled;
    }

    public void requireSubmissionEnabled() {
        if (!submissionEnabled) {
            throw new BusinessException(SubscriptionVerificationErrorCode.VERIFICATION_UNAVAILABLE);
        }
    }
}

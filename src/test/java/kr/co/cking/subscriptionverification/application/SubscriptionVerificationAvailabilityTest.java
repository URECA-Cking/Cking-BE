package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.Test;

class SubscriptionVerificationAvailabilityTest {

    @Test
    void 기본적으로_꺼진_제출은_503_업무_오류로_차단한다() {
        SubscriptionVerificationAvailability availability =
                new SubscriptionVerificationAvailability(false);

        assertThatThrownBy(availability::requireSubmissionEnabled)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SubscriptionVerificationErrorCode.VERIFICATION_UNAVAILABLE);
    }

    @Test
    void 활성화된_제출은_통과한다() {
        SubscriptionVerificationAvailability availability =
                new SubscriptionVerificationAvailability(true);

        assertThatCode(availability::requireSubmissionEnabled).doesNotThrowAnyException();
    }
}

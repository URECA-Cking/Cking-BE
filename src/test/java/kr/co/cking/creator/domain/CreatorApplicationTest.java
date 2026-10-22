package kr.co.cking.creator.domain;

import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorApplicationTest {

    @Test
    void pendingApplicationRecordsReviewerWhenApproved() {
        CreatorApplication application = new CreatorApplication(10L);

        application.approve(1L);

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.APPROVED);
        assertThat(application.getReviewedBy()).isEqualTo(1L);
        assertThat(application.getReviewedAt()).isNotNull();
    }

    @Test
    void reviewedApplicationCannotBeApprovedAgain() {
        CreatorApplication application = new CreatorApplication(10L);
        application.approve(1L);

        assertThatThrownBy(() -> application.approve(2L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_STATE);
    }

    @Test
    void pendingApplicationRecordsRejectReasonAndReviewerWhenRejected() {
        CreatorApplication application = new CreatorApplication(10L);

        application.reject(1L, "활동 정보가 부족합니다.");

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.REJECTED);
        assertThat(application.getRejectReason()).isEqualTo("활동 정보가 부족합니다.");
        assertThat(application.getReviewedBy()).isEqualTo(1L);
        assertThat(application.getReviewedAt()).isNotNull();
    }
}

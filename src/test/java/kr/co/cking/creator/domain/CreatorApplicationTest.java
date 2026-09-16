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
}

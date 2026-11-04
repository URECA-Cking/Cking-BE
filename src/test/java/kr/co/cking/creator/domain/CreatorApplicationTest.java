package kr.co.cking.creator.domain;

import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorApplicationTest {

    @Test
    void pendingApplicationRecordsReviewerWhenApproved() {
        CreatorApplication application = new CreatorApplication(10L, kr.co.cking.creator.CreatorApplicationTestForm.form("신청자"));

        application.approve(1L);

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.APPROVED);
        assertThat(application.getReviewedBy()).isEqualTo(1L);
        assertThat(application.getReviewedAt()).isNotNull();
    }

    @Test
    void reviewedApplicationCannotBeApprovedAgain() {
        CreatorApplication application = new CreatorApplication(10L, kr.co.cking.creator.CreatorApplicationTestForm.form("신청자"));
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

    @Test
    void onlyRevisionStateAcceptsDraftAndResubmission() {
        CreatorApplication application = new CreatorApplication(10L,
                kr.co.cking.creator.CreatorApplicationTestForm.form("신청자"));
        var revised = kr.co.cking.creator.CreatorApplicationTestForm.form("새활동명");

        assertThatThrownBy(() -> application.updateDraft(revised)).isInstanceOf(BusinessException.class);
        application.requestRevision("소개 보완");
        assertThatThrownBy(application::resubmit).isInstanceOf(BusinessException.class);
        application.updateDraft(revised);
        application.resubmit();

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.PENDING);
        assertThat(application.getSubmissionVersion()).isEqualTo(2);
        assertThat(application.getSubmittedForm()).isEqualTo(revised);
        assertThatThrownBy(() -> application.updateDraft(revised)).isInstanceOf(BusinessException.class);
    }

    @Test
    void legacyFormCanBeAttachedOnlyOnceWhilePending() {
        CreatorApplication application = new CreatorApplication(10L);
        var form = kr.co.cking.creator.CreatorApplicationTestForm.form("신청자");

        application.submitLegacyForm(form);

        assertThat(application.getSubmissionVersion()).isEqualTo(1);
        assertThat(application.getSubmittedForm()).isEqualTo(form);
        assertThatThrownBy(() -> application.submitLegacyForm(form)).isInstanceOf(BusinessException.class);
    }

    @Test
    void revisionStateCanBeRejectedWithoutResubmission() {
        CreatorApplication application = new CreatorApplication(10L,
                kr.co.cking.creator.CreatorApplicationTestForm.form("신청자"));
        application.requestRevision("증빙 보완");
        application.updateDraft(kr.co.cking.creator.CreatorApplicationTestForm.form("새활동명"));

        application.reject(1L, "보완 불가");

        assertThat(application.getStatus()).isEqualTo(CreatorApplicationStatus.REJECTED);
        assertThat(application.getDraftForm()).isNull();
        assertThat(application.getReviewedBy()).isEqualTo(1L);
        assertThat(application.getReviewedAt()).isNotNull();
    }
}

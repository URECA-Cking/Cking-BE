package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import kr.co.cking.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "creator_application")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CreatorApplicationStatus status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted_form", columnDefinition = "json")
    private CreatorApplicationForm submittedForm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_form", columnDefinition = "json")
    private CreatorApplicationForm draftForm;

    @Column(name = "submission_version", nullable = false)
    private int submissionVersion;

    @Column(name = "revision_reason", length = 500)
    private String revisionReason;

    public CreatorApplication(Long memberId) {
        this.memberId = memberId;
        this.status = CreatorApplicationStatus.PENDING;
        this.requestedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public CreatorApplication(Long memberId, CreatorApplicationForm form) {
        this(memberId);
        submit(form);
    }

    /** 본문 없이 접수된 PENDING 신청에만 첫 제출 버전을 연결한다. */
    public void submitLegacyForm(CreatorApplicationForm form) {
        requirePending();
        if (submittedForm != null) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_CONTENT_CONFLICT);
        }
        submit(form);
    }

    public void requestRevision(String reason) {
        requirePending();
        status = CreatorApplicationStatus.NEEDS_REVISION;
        revisionReason = reason;
        draftForm = null;
    }

    public void updateDraft(CreatorApplicationForm form) {
        if (status != CreatorApplicationStatus.NEEDS_REVISION) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_NOT_EDITABLE);
        }
        draftForm = form;
    }

    public void resubmit() {
        if (status != CreatorApplicationStatus.NEEDS_REVISION) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_NOT_EDITABLE);
        }
        if (draftForm == null) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_FORM_REQUIRED);
        }
        submit(draftForm);
        draftForm = null;
        revisionReason = null;
        status = CreatorApplicationStatus.PENDING;
    }

    private void submit(CreatorApplicationForm form) {
        submittedForm = form;
        submissionVersion++;
    }

    public void approve(Long reviewerId) {
        requirePending();
        if (submittedForm == null) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_FORM_REQUIRED);
        }
        status = CreatorApplicationStatus.APPROVED;
        reviewedBy = reviewerId;
        reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void reject(Long reviewerId, String reason) {
        if (status != CreatorApplicationStatus.PENDING && status != CreatorApplicationStatus.NEEDS_REVISION) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        status = CreatorApplicationStatus.REJECTED;
        rejectReason = reason;
        revisionReason = null;
        draftForm = null;
        reviewedBy = reviewerId;
        reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requirePending() {
        if (status != CreatorApplicationStatus.PENDING) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
    }
}

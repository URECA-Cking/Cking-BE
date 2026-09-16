package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    public CreatorApplication(Long memberId) {
        this.memberId = memberId;
        this.status = CreatorApplicationStatus.PENDING;
        this.requestedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void approve(Long reviewerId) {
        requirePending();
        status = CreatorApplicationStatus.APPROVED;
        reviewedBy = reviewerId;
        reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requirePending() {
        if (status != CreatorApplicationStatus.PENDING) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
    }
}

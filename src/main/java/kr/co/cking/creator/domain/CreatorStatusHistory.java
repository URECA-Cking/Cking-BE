package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "creator_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 20)
    private CreatorStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 20)
    private CreatorStatus newStatus;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 50)
    private String reasonCode;

    @Column(name = "detail_reason", nullable = false, updatable = false, length = 1000)
    private String detailReason;

    @Column(name = "public_notice", nullable = false, updatable = false, length = 500)
    private String publicNotice;

    @Column(name = "admin_id", nullable = false, updatable = false)
    private Long adminId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 36, unique = true)
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CreatorStatusHistory(Long creatorId, CreatorStatus previousStatus, CreatorStatus newStatus,
                                String reasonCode, String detailReason, String publicNotice,
                                Long adminId, String requestId) {
        this.creatorId = creatorId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reasonCode = reasonCode;
        this.detailReason = detailReason;
        this.publicNotice = publicNotice;
        this.adminId = adminId;
        this.requestId = requestId;
    }

    public boolean matches(Long creatorId, CreatorStatus next, String reasonCode, String detailReason,
                           String publicNotice, Long adminId) {
        return this.creatorId.equals(creatorId) && this.newStatus == next && this.reasonCode.equals(reasonCode)
                && this.detailReason.equals(detailReason) && this.publicNotice.equals(publicNotice)
                && this.adminId.equals(adminId);
    }

    @PrePersist
    void assignCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

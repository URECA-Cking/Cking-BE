package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "creator_application_review_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorApplicationReviewHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "submission_version", nullable = false)
    private int submissionVersion;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public CreatorApplicationReviewHistory(
            Long applicationId, String action, int submissionVersion, Long actorId, String reason
    ) {
        this.applicationId = applicationId;
        this.action = action;
        this.submissionVersion = submissionVersion;
        this.actorId = actorId;
        this.reason = reason;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

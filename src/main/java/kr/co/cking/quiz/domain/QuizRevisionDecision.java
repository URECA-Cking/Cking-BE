package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

/**
 * revision에 대한 Creator의 승인·거절. revision마다 하나이며 바꾸지 않는다.
 *
 * <p>승인은 Judge 평가 근거(평가 ID와 해시)를 함께 기록한다. 거절은 근거 없이 기록할 수 있다.
 */
@Entity
@Immutable
@Table(name = "quiz_revision_decision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRevisionDecision {

    public static final int MAX_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_id")
    private Long decisionId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 20)
    private QuizRevisionDecisionType decision;

    @Column(name = "decided_by_member_id", nullable = false, updatable = false)
    private Long decidedByMemberId;

    @Column(name = "reason", updatable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "judge_evaluation_id", updatable = false)
    private Long judgeEvaluationId;

    @Column(name = "judge_evaluation_sha256", updatable = false, length = 64)
    private String judgeEvaluationSha256;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    private QuizRevisionDecision(Long revisionId, QuizRevisionDecisionType decision, Long decidedByMemberId,
                                 String reason, Long judgeEvaluationId, String judgeEvaluationSha256,
                                 Instant decidedAt) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.decision = decision;
        this.decidedByMemberId = Objects.requireNonNull(decidedByMemberId, "decidedByMemberId");
        this.reason = reason;
        this.judgeEvaluationId = judgeEvaluationId;
        this.judgeEvaluationSha256 = judgeEvaluationSha256;
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public static QuizRevisionDecision approve(Long revisionId, Long decidedByMemberId, Long judgeEvaluationId,
                                               String judgeEvaluationSha256, Instant now) {
        return new QuizRevisionDecision(revisionId, QuizRevisionDecisionType.APPROVED, decidedByMemberId, null,
                Objects.requireNonNull(judgeEvaluationId, "judgeEvaluationId"),
                Objects.requireNonNull(judgeEvaluationSha256, "judgeEvaluationSha256"), now);
    }

    public static QuizRevisionDecision reject(Long revisionId, Long decidedByMemberId, String reason, Instant now) {
        return new QuizRevisionDecision(revisionId, QuizRevisionDecisionType.REJECTED, decidedByMemberId, reason,
                null, null, now);
    }

    public boolean isApproved() {
        return decision == QuizRevisionDecisionType.APPROVED;
    }
}

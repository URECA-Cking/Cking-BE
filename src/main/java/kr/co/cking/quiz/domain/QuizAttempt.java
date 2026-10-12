package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/** 제출·채점 결과의 불변 헤더. Ticket 지급은 수행하지 않는다. */
@Entity
@Immutable
@Table(name = "quiz_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long attemptId;
    @Column(nullable = false, updatable = false) private Long memberId;
    @Column(nullable = false, updatable = false) private Long creatorId;
    @Column(nullable = false, updatable = false) private Long quizId;
    @Column(nullable = false, updatable = false) private Long revisionId;
    @Column(nullable = false, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, updatable = false, length = 64) private String requestFingerprint;
    @Column(nullable = false, updatable = false, length = 64) private String revisionSha256;
    @Column(nullable = false, updatable = false, length = 64) private String snapshotSha256;
    @Column(nullable = false, updatable = false, length = 100) private String title;
    @Column(nullable = false, updatable = false, length = 20) private String videoId;
    @Column(nullable = false, updatable = false) private int questionCount;
    @Column(nullable = false, updatable = false) private int correctCount;
    @Column(nullable = false, updatable = false) private long rewardAmount;
    @Column(updatable = false, length = 36) private String rewardRequestId;
    @Column(nullable = false, updatable = false, length = 20) private String rewardStatus;
    @Column(nullable = false, updatable = false) private Instant submittedAt;

    public QuizAttempt(Long memberId, Long creatorId, Long quizId, Long revisionId, String requestId,
            String requestFingerprint, String revisionSha256, String snapshotSha256, String title, String videoId,
            int questionCount, int correctCount, String rewardRequestId, Instant submittedAt) {
        this.memberId = memberId;
        this.creatorId = creatorId;
        this.quizId = quizId;
        this.revisionId = revisionId;
        this.requestId = requestId;
        this.requestFingerprint = requestFingerprint;
        this.revisionSha256 = revisionSha256;
        this.snapshotSha256 = snapshotSha256;
        this.title = title;
        this.videoId = videoId;
        this.questionCount = questionCount;
        this.correctCount = correctCount;
        this.rewardAmount = correctCount;
        this.rewardRequestId = rewardRequestId;
        this.rewardStatus = correctCount > 0 ? "NOT_INTEGRATED" : "NOT_ELIGIBLE";
        this.submittedAt = submittedAt;
    }
}

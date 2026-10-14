package kr.co.cking.quiz.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/** 성공한 편집과 같은 트랜잭션에 저장하는 불변 접수 기록. */
@Entity @Immutable @Table(name = "quiz_composition_request") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizCompositionRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long quizId;
    @Column(nullable = false, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, updatable = false, length = 64) private String payloadSha256;
    @Column(nullable = false, updatable = false) private Long revisionId;
    @Column(nullable = false, updatable = false) private Long requestedByMemberId;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    public QuizCompositionRequest(Long quizId, String requestId, String payloadSha256, Long revisionId, Long memberId, Instant now) {
        this.quizId = quizId; this.requestId = requestId; this.payloadSha256 = payloadSha256;
        this.revisionId = revisionId; this.requestedByMemberId = memberId; this.createdAt = now;
    }
}

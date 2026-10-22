package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 세대에 속한 후보 Creator와 점수·순위다. */
@Entity
@Table(name = "interest_recommendation_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestRecommendationCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "generation_id", nullable = false, updatable = false)
    private Long generationId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "score", nullable = false, updatable = false, precision = 12, scale = 8)
    private BigDecimal score;

    @Column(name = "rank_no", nullable = false, updatable = false)
    private int rank;

    public InterestRecommendationCandidate(Long generationId, Long creatorId, BigDecimal score, int rank) {
        this.generationId = generationId;
        this.creatorId = creatorId;
        this.score = score;
        this.rank = rank;
    }
}

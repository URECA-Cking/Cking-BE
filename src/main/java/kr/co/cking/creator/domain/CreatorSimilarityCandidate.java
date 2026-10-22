package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 특정 추천 세대에 속한 유사 크리에이터 후보 한 건이다. */
@Entity
@Table(name = "creator_similarity_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSimilarityCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "generation_id", nullable = false, updatable = false)
    private Long generationId;

    @Column(name = "similar_creator_id", nullable = false, updatable = false)
    private Long similarCreatorId;

    @Column(name = "score", nullable = false, updatable = false, precision = 12, scale = 8)
    private BigDecimal score;

    @Column(name = "rank_no", nullable = false, updatable = false)
    private int rank;

    public CreatorSimilarityCandidate(
            Long generationId,
            Long similarCreatorId,
            BigDecimal score,
            int rank
    ) {
        this.generationId = generationId;
        this.similarCreatorId = similarCreatorId;
        this.score = score;
        this.rank = rank;
    }
}

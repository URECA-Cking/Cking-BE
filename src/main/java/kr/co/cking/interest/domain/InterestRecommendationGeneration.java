package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LLM이 만든 관심 분야별 추천 후보 한 묶음의 불변 메타데이터다. */
@Entity
@Table(name = "interest_recommendation_generation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestRecommendationGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "generation_id")
    private Long generationId;

    @Column(name = "taxonomy_version", nullable = false, updatable = false, length = 20)
    private String taxonomyVersion;

    @Column(name = "interest_code", nullable = false, updatable = false, length = 30)
    private String interestCode;

    @Column(name = "method", nullable = false, updatable = false, length = 20)
    private String method;

    @Column(name = "model_version", nullable = false, updatable = false, length = 255)
    private String modelVersion;

    @Column(name = "input_hash", nullable = false, updatable = false, length = 64)
    private String inputHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InterestRecommendationGeneration(
            String taxonomyVersion,
            String interestCode,
            String method,
            String modelVersion,
            String inputHash,
            Instant createdAt
    ) {
        this.taxonomyVersion = taxonomyVersion;
        this.interestCode = interestCode;
        this.method = method;
        this.modelVersion = modelVersion;
        this.inputHash = inputHash;
        this.createdAt = createdAt;
    }
}

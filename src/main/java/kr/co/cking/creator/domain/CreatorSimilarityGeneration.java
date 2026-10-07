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

import java.time.Instant;

/** LLM이 생성한 단일 크리에이터 유사 추천 후보 한 묶음의 불변 메타데이터다. */
@Entity
@Table(name = "creator_similarity_generation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSimilarityGeneration {

    @Column(name = "application_sequence", nullable = false, updatable = false)
    private long applicationSequence;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "generation_id")
    private Long generationId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "method", nullable = false, updatable = false, length = 20)
    private String method;

    @Column(name = "model_version", nullable = false, updatable = false, length = 255)
    private String modelVersion;

    @Column(name = "input_hash", nullable = false, updatable = false, length = 64)
    private String inputHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CreatorSimilarityGeneration(
            long applicationSequence,
            Long creatorId,
            String method,
            String modelVersion,
            String inputHash,
            Instant createdAt
    ) {
        this.applicationSequence = applicationSequence;
        this.creatorId = creatorId;
        this.method = method;
        this.modelVersion = modelVersion;
        this.inputHash = inputHash;
        this.createdAt = createdAt;
    }
}

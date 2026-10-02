package kr.co.cking.creator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공개 조회에 사용할 완성된 추천 세대를 가리키는 크리에이터별 포인터다. */
@Entity
@Table(name = "creator_similarity_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSimilarityState {

    @Id
    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "current_generation_id", nullable = false)
    private Long currentGenerationId;

    public CreatorSimilarityState(Long creatorId, Long currentGenerationId) {
        this.creatorId = creatorId;
        this.currentGenerationId = currentGenerationId;
    }

    public void activate(Long generationId) {
        this.currentGenerationId = generationId;
    }
}

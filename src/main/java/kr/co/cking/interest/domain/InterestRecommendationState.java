package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분야별로 현재 공개할 완성 세대를 가리키는 포인터다. */
@Entity
@Table(name = "interest_recommendation_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestRecommendationState {

    @EmbeddedId
    private InterestCategoryId id;

    @Column(name = "current_generation_id", nullable = false)
    private Long currentGenerationId;

    public InterestRecommendationState(String taxonomyVersion, String interestCode, Long currentGenerationId) {
        this.id = new InterestCategoryId(taxonomyVersion, interestCode);
        this.currentGenerationId = currentGenerationId;
    }

    public void activate(Long generationId) {
        this.currentGenerationId = generationId;
    }
}

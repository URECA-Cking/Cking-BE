package kr.co.cking.interest.repository;

import kr.co.cking.interest.domain.InterestCategoryId;
import kr.co.cking.interest.domain.InterestRecommendationState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestRecommendationStateRepository
        extends JpaRepository<InterestRecommendationState, InterestCategoryId> {
}

package kr.co.cking.interest.repository;

import java.util.Optional;
import kr.co.cking.interest.domain.InterestRecommendationGeneration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestRecommendationGenerationRepository
        extends JpaRepository<InterestRecommendationGeneration, Long> {

    Optional<InterestRecommendationGeneration> findByTaxonomyVersionAndInterestCodeAndInputHash(
            String taxonomyVersion, String interestCode, String inputHash);
}

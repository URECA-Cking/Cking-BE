package kr.co.cking.interest.repository;

import java.util.List;
import kr.co.cking.interest.domain.InterestRecommendationCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestRecommendationCandidateRepository
        extends JpaRepository<InterestRecommendationCandidate, Long> {

    List<InterestRecommendationCandidate> findByGenerationIdOrderByRankAsc(Long generationId);
}

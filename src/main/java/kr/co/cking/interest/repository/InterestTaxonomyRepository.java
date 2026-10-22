package kr.co.cking.interest.repository;

import java.util.Optional;
import kr.co.cking.interest.domain.InterestTaxonomy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestTaxonomyRepository extends JpaRepository<InterestTaxonomy, String> {

    /** 활성 분류체계는 DB 제약으로 최대 1개다. */
    Optional<InterestTaxonomy> findByActiveTrue();
}

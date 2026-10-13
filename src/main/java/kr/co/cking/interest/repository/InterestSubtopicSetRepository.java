package kr.co.cking.interest.repository;

import java.util.Optional;
import kr.co.cking.interest.domain.InterestSubtopicSet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestSubtopicSetRepository extends JpaRepository<InterestSubtopicSet, String> {

    /** 활성 세부 세트는 DB 제약으로 최대 1개다. */
    Optional<InterestSubtopicSet> findByActiveTrue();
}

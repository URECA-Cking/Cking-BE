package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSimilarityCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CreatorSimilarityCandidateRepository extends JpaRepository<CreatorSimilarityCandidate, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from CreatorSimilarityCandidate c
            where c.generationId = :generationId
            order by c.rank asc
            """)
    List<CreatorSimilarityCandidate> findByGenerationIdForUpdateOrderByRankAsc(
            @Param("generationId") Long generationId
    );

    List<CreatorSimilarityCandidate> findByGenerationIdOrderByRankAsc(Long generationId, Pageable pageable);
}

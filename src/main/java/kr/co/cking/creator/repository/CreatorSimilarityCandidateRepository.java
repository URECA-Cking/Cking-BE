package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSimilarityCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /** 여러 seed Creator의 현재 활성 세대 후보를 한 번에 읽어 N+1 조회를 방지한다. */
    @Query("""
            select new kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate(
                s.creatorId, c.similarCreatorId, g.method, c.score, c.rank
            )
            from CreatorSimilarityState s, CreatorSimilarityGeneration g, CreatorSimilarityCandidate c
            where s.creatorId in :seedCreatorIds
              and g.generationId = s.currentGenerationId
              and c.generationId = g.generationId
            order by s.creatorId asc, c.rank asc, c.similarCreatorId asc
            """)
    List<ActiveCreatorRecommendationCandidate> findActiveCandidatesBySeedCreatorIds(
            @Param("seedCreatorIds") Collection<Long> seedCreatorIds
    );
}

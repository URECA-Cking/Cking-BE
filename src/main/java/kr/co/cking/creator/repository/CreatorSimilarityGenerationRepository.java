package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSimilarityGeneration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorSimilarityGenerationRepository
        extends JpaRepository<CreatorSimilarityGeneration, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select g from CreatorSimilarityGeneration g
            where g.creatorId = :creatorId and g.applicationSequence = :applicationSequence
            """)
    Optional<CreatorSimilarityGeneration> findByCreatorIdAndApplicationSequenceForUpdate(
            @Param("creatorId") Long creatorId,
            @Param("applicationSequence") Long applicationSequence
    );
}

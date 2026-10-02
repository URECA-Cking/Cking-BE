package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSimilarityState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorSimilarityStateRepository extends JpaRepository<CreatorSimilarityState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreatorSimilarityState s where s.creatorId = :creatorId")
    Optional<CreatorSimilarityState> findByCreatorIdForUpdate(@Param("creatorId") Long creatorId);
}

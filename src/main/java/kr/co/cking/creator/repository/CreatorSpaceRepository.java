package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSpace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorSpaceRepository extends JpaRepository<CreatorSpace, Long> {

    Optional<CreatorSpace> findByCreatorId(Long creatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreatorSpace s where s.creatorId = :creatorId")
    Optional<CreatorSpace> findByCreatorIdForUpdate(@Param("creatorId") Long creatorId);

    Optional<CreatorSpace> findBySlug(String slug);

    boolean existsBySlug(String slug);
}

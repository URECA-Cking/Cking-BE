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

    /** 공유 URL의 slug로 Creator Space를 조회한다. */
    Optional<CreatorSpace> findBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreatorSpace s where s.creatorId = :creatorId")
    Optional<CreatorSpace> findByCreatorIdForUpdate(@Param("creatorId") Long creatorId);

    boolean existsBySlug(String slug);
}

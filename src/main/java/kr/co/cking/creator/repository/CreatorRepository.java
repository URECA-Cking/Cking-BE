package kr.co.cking.creator.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.creator.domain.Creator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreatorRepository extends JpaRepository<Creator, Long> {

    Optional<Creator> findByMemberId(Long memberId);

    /** Event 캐시 적재에 필요한 Creator 소유 회원 ID만 조회한다. */
    @Query("select c.memberId from Creator c where c.creatorId = :creatorId")
    Optional<Long> findOwnerMemberId(@Param("creatorId") Long creatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Creator c where c.memberId = :memberId")
    Optional<Creator> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Creator c where c.creatorId = :creatorId")
    Optional<Creator> findByIdForUpdate(@Param("creatorId") Long creatorId);

    boolean existsByMemberId(Long memberId);

    boolean existsByActivityNameNormalized(String activityNameNormalized);

    List<Creator> findByCreatorIdIn(Collection<Long> creatorIds);

    List<Creator> findByMemberIdIn(Collection<Long> memberIds);
}

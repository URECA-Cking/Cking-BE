package kr.co.cking.sanction.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MemberSanctionRepository extends JpaRepository<MemberSanction, Long> {

    Optional<MemberSanction> findByCauseCommentId(Long causeCommentId);

    /** since 이후에 시작된 자동 경고 수. 집계 기간 안의 경고만 센다(기록은 지우지 않는다). */
    long countByMemberIdAndSourceAndStartedAtGreaterThanEqual(Long memberId, SanctionSource source, Instant since);

    /**
     * 지금 유효한 제한 중 가장 늦게 끝나는 시각. 제한이 겹치면 종료 시각이 더 늦은 쪽이 유지되는 효과를 낸다.
     * 유효한 제한이 없으면 비어 있다.
     */
    @Query("""
            select max(s.endsAt) from MemberSanction s
            where s.memberId = :memberId and s.scope = :scope and s.type = :type
              and s.startedAt <= :now and s.endsAt > :now
              and (s.releasedAt is null or s.releasedAt > :now)
            """)
    Optional<Instant> findActiveEndsAt(
            @Param("memberId") Long memberId,
            @Param("scope") SanctionScope scope,
            @Param("type") SanctionType type,
            @Param("now") Instant now);

    /** 한 스페이스에서 그 회원에게 지금 유효한 차단 중 가장 늦게 끝나는 시각. 유효한 차단이 없으면 비어 있다. */
    @Query("""
            select max(s.endsAt) from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.SPACE
              and s.type = kr.co.cking.sanction.domain.SanctionType.COMMENT_RESTRICTION
              and s.spaceCreatorId = :creatorId and s.memberId = :memberId
              and s.startedAt <= :now and s.endsAt > :now
              and (s.releasedAt is null or s.releasedAt > :now)
            """)
    Optional<Instant> findActiveSpaceBlockEndsAt(
            @Param("creatorId") Long creatorId, @Param("memberId") Long memberId, @Param("now") Instant now);

    /** 한 스페이스에서 그 회원에게 지금 유효한 차단. 같은 쌍에는 유효한 차단을 하나만 둔다. */
    @Query("""
            select s from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.SPACE
              and s.type = kr.co.cking.sanction.domain.SanctionType.COMMENT_RESTRICTION
              and s.spaceCreatorId = :creatorId and s.memberId = :memberId
              and s.startedAt <= :now and s.endsAt > :now
              and (s.releasedAt is null or s.releasedAt > :now)
            order by s.endsAt desc, s.sanctionId desc
            """)
    java.util.List<MemberSanction> findActiveSpaceBlocks(
            @Param("creatorId") Long creatorId, @Param("memberId") Long memberId, @Param("now") Instant now);

    /** 크리에이터의 차단 목록. 최신 시작 순이며 activeOnly면 지금 유효한 차단만 돌려준다. */
    @Query(value = """
            select s from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.SPACE and s.spaceCreatorId = :creatorId
              and (:activeOnly = false
                   or (s.startedAt <= :now and s.endsAt > :now and (s.releasedAt is null or s.releasedAt > :now)))
            order by s.startedAt desc, s.sanctionId desc
            """,
            countQuery = """
            select count(s) from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.SPACE and s.spaceCreatorId = :creatorId
              and (:activeOnly = false
                   or (s.startedAt <= :now and s.endsAt > :now and (s.releasedAt is null or s.releasedAt > :now)))
            """)
    Page<MemberSanction> searchSpaceBlocks(
            @Param("creatorId") Long creatorId,
            @Param("activeOnly") boolean activeOnly,
            @Param("now") Instant now,
            Pageable pageable);

    /** 관리자용 스페이스 차단 목록. creatorId·memberId가 null이면 거르지 않고, 최신 시작 순이다. */
    @Query(value = """
            select s from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.SPACE
              and (:creatorId is null or s.spaceCreatorId = :creatorId)
              and (:memberId is null or s.memberId = :memberId)
              and (:activeOnly = false
                   or (s.startedAt <= :now and s.endsAt > :now and (s.releasedAt is null or s.releasedAt > :now)))
            order by s.startedAt desc, s.sanctionId desc
            """,
            countQuery = """
            select count(s) from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.SPACE
              and (:creatorId is null or s.spaceCreatorId = :creatorId)
              and (:memberId is null or s.memberId = :memberId)
              and (:activeOnly = false
                   or (s.startedAt <= :now and s.endsAt > :now and (s.releasedAt is null or s.releasedAt > :now)))
            """)
    Page<MemberSanction> searchSpaceBlocksForAdmin(
            @Param("creatorId") Long creatorId,
            @Param("memberId") Long memberId,
            @Param("activeOnly") boolean activeOnly,
            @Param("now") Instant now,
            Pageable pageable);

    /** 관리자 목록. 최신 시작 순이며 memberId를 주면 그 회원의 제재만, activeOnly면 지금 유효한 제재만 돌려준다. */
    @Query(value = """
            select s from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.PLATFORM
              and (:memberId is null or s.memberId = :memberId)
              and (:activeOnly = false
                   or (s.startedAt <= :now and s.endsAt > :now and (s.releasedAt is null or s.releasedAt > :now)))
            order by s.startedAt desc, s.sanctionId desc
            """,
            countQuery = """
            select count(s) from MemberSanction s
            where s.scope = kr.co.cking.sanction.domain.SanctionScope.PLATFORM
              and (:memberId is null or s.memberId = :memberId)
              and (:activeOnly = false
                   or (s.startedAt <= :now and s.endsAt > :now and (s.releasedAt is null or s.releasedAt > :now)))
            """)
    Page<MemberSanction> search(
            @Param("memberId") Long memberId,
            @Param("activeOnly") boolean activeOnly,
            @Param("now") Instant now,
            Pageable pageable);

    /** 같은 제재의 동시 해제를 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MemberSanction s where s.sanctionId = :sanctionId")
    Optional<MemberSanction> findByIdForUpdate(@Param("sanctionId") Long sanctionId);
}

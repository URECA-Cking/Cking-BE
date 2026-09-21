package kr.co.cking.ticket.repository;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalanceId;

public interface UserTicketBalanceRepository extends JpaRepository<UserTicketBalance, UserTicketBalanceId> {

    @Query("select b from UserTicketBalance b where b.id.memberId = :memberId and b.id.creatorId = :creatorId")
    Optional<UserTicketBalance> findByMemberIdAndCreatorId(@Param("memberId") Long memberId,
                                                            @Param("creatorId") Long creatorId);

    // EARN/SPEND Consumer가 같은 (memberId, creatorId) 행을 동시에 갱신할 수 있으므로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from UserTicketBalance b where b.id.memberId = :memberId and b.id.creatorId = :creatorId")
    Optional<UserTicketBalance> findByMemberIdAndCreatorIdForUpdate(@Param("memberId") Long memberId,
                                                                     @Param("creatorId") Long creatorId);

    // 정합성 배치용 keyset 조회. offset 방식은 조회 중 앞선 키가 삽입되면 같은 행을 다시 읽어 한 주기에
    // 같은 키가 두 번 집계될 수 있다. (memberId, creatorId)가 마지막 키보다 큰 행만 읽는다.
    @Query("""
            select b from UserTicketBalance b
            where b.id.memberId > :memberId or (b.id.memberId = :memberId and b.id.creatorId > :creatorId)
            order by b.id.memberId, b.id.creatorId
            """)
    List<UserTicketBalance> findNextBatch(@Param("memberId") Long memberId,
                                          @Param("creatorId") Long creatorId,
                                          Pageable pageable);
}

package kr.co.cking.ticket.repository;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Slice<UserTicketBalance> findAllBy(Pageable pageable);
}

package kr.co.cking.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserTicketBalanceRepository extends JpaRepository<UserTicketBalance, UserTicketBalanceId> {

    @Query("select b from UserTicketBalance b where b.id.memberId = :memberId and b.id.creatorId = :creatorId")
    Optional<UserTicketBalance> findByMemberIdAndCreatorId(@Param("memberId") Long memberId,
                                                            @Param("creatorId") Long creatorId);
}

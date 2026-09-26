package kr.co.cking.ticket.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserCommonTicketBalanceRepository extends JpaRepository<UserCommonTicketBalance, Long> {

    // EARN Consumer가 같은 memberId 행을 동시에 갱신할 수 있으므로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from UserCommonTicketBalance b where b.memberId = :memberId")
    Optional<UserCommonTicketBalance> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    // 정합성 배치용 keyset 조회(UserTicketBalanceRepository.findNextBatch와 같은 이유).
    @Query("select b from UserCommonTicketBalance b where b.memberId > :memberId order by b.memberId")
    List<UserCommonTicketBalance> findNextBatch(@Param("memberId") Long memberId, Pageable pageable);
}

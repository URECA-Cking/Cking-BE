package kr.co.cking.winner.repository;

import java.util.Optional;
import kr.co.cking.winner.domain.WinnerManagement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WinnerManagementRepository extends JpaRepository<WinnerManagement, Long> {

    Optional<WinnerManagement> findByWinnerId(Long winnerId);
}

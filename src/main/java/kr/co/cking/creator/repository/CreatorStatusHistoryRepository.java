package kr.co.cking.creator.repository;

import java.util.Optional;
import kr.co.cking.creator.domain.CreatorStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorStatusHistoryRepository extends JpaRepository<CreatorStatusHistory, Long> {
    Optional<CreatorStatusHistory> findByRequestId(String requestId);

    Page<CreatorStatusHistory> findByCreatorIdOrderByIdDesc(Long creatorId, Pageable pageable);
}

package kr.co.cking.creator.repository;

import java.util.List;
import kr.co.cking.creator.domain.CreatorApplicationReviewHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorApplicationReviewHistoryRepository extends JpaRepository<CreatorApplicationReviewHistory, Long> {
    List<CreatorApplicationReviewHistory> findByApplicationIdOrderByIdAsc(Long applicationId);
}

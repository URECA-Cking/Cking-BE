package kr.co.cking.creator.repository;

import java.util.List;
import kr.co.cking.creator.domain.CreatorApplicationSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorApplicationSubmissionRepository extends JpaRepository<CreatorApplicationSubmission, Long> {
    List<CreatorApplicationSubmission> findByApplicationIdOrderByVersionAsc(Long applicationId);
}

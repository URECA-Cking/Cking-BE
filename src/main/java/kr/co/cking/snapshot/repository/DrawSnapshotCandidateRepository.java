package kr.co.cking.snapshot.repository;

import java.util.List;
import kr.co.cking.snapshot.domain.DrawSnapshotCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawSnapshotCandidateRepository extends JpaRepository<DrawSnapshotCandidate, Long> {

    List<DrawSnapshotCandidate> findAllBySnapshot_IdOrderByMemberIdAsc(Long snapshotId);

    long countBySnapshot_Id(Long snapshotId);
}

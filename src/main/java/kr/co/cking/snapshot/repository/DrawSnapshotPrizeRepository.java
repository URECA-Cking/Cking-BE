package kr.co.cking.snapshot.repository;

import java.util.List;
import kr.co.cking.snapshot.domain.DrawSnapshotPrize;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawSnapshotPrizeRepository extends JpaRepository<DrawSnapshotPrize, Long> {
    List<DrawSnapshotPrize> findAllBySnapshot_IdOrderByPriorityAscPrizeKeyAsc(Long snapshotId);
}

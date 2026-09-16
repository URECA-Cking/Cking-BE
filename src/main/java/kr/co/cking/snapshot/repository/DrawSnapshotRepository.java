package kr.co.cking.snapshot.repository;

import java.util.Optional;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawSnapshotRepository extends JpaRepository<DrawSnapshot, Long> {

    Optional<DrawSnapshot> findByEventId(Long eventId);

    long countByEventId(Long eventId);
}

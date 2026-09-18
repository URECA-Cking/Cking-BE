package kr.co.cking.snapshot.repository;

import java.util.List;
import java.util.Optional;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;

public interface SnapshotSourceQueryRepository {

    Optional<SnapshotEventSource> findEventForUpdate(Long eventId);

    List<CandidateValue> findCandidates(Long eventId);

    List<PrizeValue> findPrizes(Long eventId);
}

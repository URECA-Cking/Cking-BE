package kr.co.cking.snapshot.repository;

import java.time.Instant;

/** Snapshot 복구 실패의 지수 백오프 상태를 영속화한다. */
public interface SnapshotRecoveryFailureRepository {

    void recordFailure(Long eventId, Instant failedAt, String failureCode, String failureMessage);

    void clear(Long eventId);
}

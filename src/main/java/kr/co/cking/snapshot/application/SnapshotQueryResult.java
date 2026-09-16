package kr.co.cking.snapshot.application;

import java.time.Instant;
import java.util.List;
import kr.co.cking.snapshot.domain.DrawSnapshot;

public record SnapshotQueryResult(
        Long snapshotId,
        Long eventId,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        int candidateCount,
        long totalTicketCount,
        String snapshotHash,
        Instant createdAt,
        List<SnapshotCandidateResult> candidates
) {

    public SnapshotQueryResult {
        candidates = List.copyOf(candidates);
    }

    public static SnapshotQueryResult from(
            DrawSnapshot snapshot,
            List<SnapshotCandidateResult> candidates
    ) {
        return new SnapshotQueryResult(
                snapshot.getId(),
                snapshot.getEventId(),
                snapshot.getWinnerCount(),
                snapshot.getDrawMethod(),
                snapshot.getAlgorithmVersion(),
                snapshot.getCandidateCount(),
                snapshot.getTotalTicketCount(),
                snapshot.getSnapshotHash(),
                snapshot.getCreatedAt(),
                candidates
        );
    }
}

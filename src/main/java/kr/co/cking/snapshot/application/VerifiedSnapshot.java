package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;

public record VerifiedSnapshot(
        Long snapshotId,
        Long eventId,
        int candidateCount,
        long totalTicketCount,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        String snapshotHash,
        List<CandidateValue> candidates
) {
    public VerifiedSnapshot {
        candidates = List.copyOf(candidates);
    }

    static VerifiedSnapshot from(DrawSnapshot snapshot, List<CandidateValue> candidates) {
        return new VerifiedSnapshot(
                snapshot.getId(),
                snapshot.getEventId(),
                snapshot.getCandidateCount(),
                snapshot.getTotalTicketCount(),
                snapshot.getWinnerCount(),
                snapshot.getDrawMethod(),
                snapshot.getAlgorithmVersion(),
                snapshot.getSnapshotHash(),
                candidates
        );
    }
}

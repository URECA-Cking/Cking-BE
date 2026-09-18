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
        String prizeAlgorithmVersion,
        int candidateCount,
        long totalTicketCount,
        String snapshotHash,
        Instant createdAt,
        List<SnapshotCandidateResult> candidates,
        List<SnapshotPrizeResult> prizes
) {

    public SnapshotQueryResult(Long snapshotId, Long eventId, int winnerCount, String drawMethod,
            String algorithmVersion, int candidateCount, long totalTicketCount, String snapshotHash,
            Instant createdAt, List<SnapshotCandidateResult> candidates) {
        this(snapshotId, eventId, winnerCount, drawMethod, algorithmVersion, "PRIZE_WEIGHTED_V1",
                candidateCount, totalTicketCount, snapshotHash, createdAt, candidates, List.of());
    }

    public SnapshotQueryResult {
        candidates = List.copyOf(candidates);
        prizes = List.copyOf(prizes);
    }

    public static SnapshotQueryResult from(
            DrawSnapshot snapshot,
            List<SnapshotCandidateResult> candidates,
            List<SnapshotPrizeResult> prizes
    ) {
        return new SnapshotQueryResult(
                snapshot.getId(),
                snapshot.getEventId(),
                snapshot.getWinnerCount(),
                snapshot.getDrawMethod(),
                snapshot.getAlgorithmVersion(),
                snapshot.getPrizeAlgorithmVersion(),
                snapshot.getCandidateCount(),
                snapshot.getTotalTicketCount(),
                snapshot.getSnapshotHash(),
                snapshot.getCreatedAt(),
                candidates,
                prizes
        );
    }
}

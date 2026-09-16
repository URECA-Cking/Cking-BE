package kr.co.cking.snapshot.application;

import kr.co.cking.snapshot.domain.DrawSnapshot;

public record OfficialSnapshotResult(
        Long snapshotId,
        Long eventId,
        int candidateCount,
        long totalTicketCount,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        String snapshotHash
) {
    public static OfficialSnapshotResult from(DrawSnapshot snapshot) {
        return new OfficialSnapshotResult(
                snapshot.getId(),
                snapshot.getEventId(),
                snapshot.getCandidateCount(),
                snapshot.getTotalTicketCount(),
                snapshot.getWinnerCount(),
                snapshot.getDrawMethod(),
                snapshot.getAlgorithmVersion(),
                snapshot.getSnapshotHash()
        );
    }
}

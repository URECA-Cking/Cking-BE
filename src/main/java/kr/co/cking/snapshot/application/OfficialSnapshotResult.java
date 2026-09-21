package kr.co.cking.snapshot.application;

import kr.co.cking.snapshot.domain.DrawSnapshot;
import java.util.List;

public record OfficialSnapshotResult(
        Long snapshotId,
        Long eventId,
        int candidateCount,
        long totalTicketCount,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        String snapshotHash,
        String prizeAlgorithmVersion,
        List<SnapshotPrizeResult> prizes
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
                snapshot.getSnapshotHash(),
                snapshot.getPrizeAlgorithmVersion(),
                snapshot.getPrizes().stream().map(SnapshotPrizeResult::from).toList()
        );
    }
}

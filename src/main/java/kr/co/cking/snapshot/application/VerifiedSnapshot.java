package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;

/** SnapshotIntegrityService의 검증을 통과한 공식 Snapshot 결과다. */
public final class VerifiedSnapshot {

    private final Long snapshotId;
    private final Long eventId;
    private final int candidateCount;
    private final long totalTicketCount;
    private final int winnerCount;
    private final String drawMethod;
    private final String algorithmVersion;
    private final String snapshotHash;
    private final List<CandidateValue> candidates;

    private VerifiedSnapshot(
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
        this.snapshotId = snapshotId;
        this.eventId = eventId;
        this.candidateCount = candidateCount;
        this.totalTicketCount = totalTicketCount;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod;
        this.algorithmVersion = algorithmVersion;
        this.snapshotHash = snapshotHash;
        this.candidates = List.copyOf(candidates);
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

    public Long snapshotId() {
        return snapshotId;
    }

    public Long eventId() {
        return eventId;
    }

    public int candidateCount() {
        return candidateCount;
    }

    public long totalTicketCount() {
        return totalTicketCount;
    }

    public int winnerCount() {
        return winnerCount;
    }

    public String drawMethod() {
        return drawMethod;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }

    public String snapshotHash() {
        return snapshotHash;
    }

    public List<CandidateValue> candidates() {
        return candidates;
    }
}

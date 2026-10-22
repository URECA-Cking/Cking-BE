package kr.co.cking.snapshot.application;

import kr.co.cking.snapshot.domain.DrawSnapshotCandidate;

public record SnapshotCandidateResult(
        Long userId,
        long ticketCount
) {

    public static SnapshotCandidateResult from(DrawSnapshotCandidate candidate) {
        return new SnapshotCandidateResult(candidate.getMemberId(), candidate.getTicketCount());
    }
}

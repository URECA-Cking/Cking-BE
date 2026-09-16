package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;

public record SnapshotHashInput(
        Long eventId,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        List<CandidateValue> candidates
) {
    public SnapshotHashInput {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates는 필수입니다.");
        }
        candidates = List.copyOf(candidates);
    }
}

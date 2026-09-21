package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;

/** 상품 설정까지 포함하는 CKING_SNAPSHOT_V2 Hash 입력이다. */
public record SnapshotHashV2Input(
        Long eventId,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        String prizeAlgorithmVersion,
        List<CandidateValue> candidates,
        List<PrizeValue> prizes
) {
    public SnapshotHashV2Input {
        if (candidates == null || prizes == null) {
            throw new IllegalArgumentException("후보와 상품 설정은 필수입니다.");
        }
        candidates = candidates.stream().sorted(CandidateValue.BY_MEMBER_ID).toList();
        prizes = prizes.stream()
                .sorted(java.util.Comparator.comparingInt(PrizeValue::priority).thenComparing(PrizeValue::prizeKey))
                .toList();
    }
}

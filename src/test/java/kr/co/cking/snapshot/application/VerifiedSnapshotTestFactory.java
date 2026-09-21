package kr.co.cking.snapshot.application;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.PrizeValue;
import org.springframework.test.util.ReflectionTestUtils;

/** 다른 패키지의 테스트가 검증 완료 결과를 준비할 때만 사용하는 테스트 Fixture다. */
public final class VerifiedSnapshotTestFactory {

    private VerifiedSnapshotTestFactory() {
    }

    public static VerifiedSnapshot create(
            Long snapshotId,
            Long eventId,
            int winnerCount,
            String drawMethod,
            String algorithmVersion
    ) {
        DrawSnapshot snapshot = DrawSnapshot.create(
                eventId,
                winnerCount,
                drawMethod,
                algorithmVersion,
                "0".repeat(64),
                List.<CandidateValue>of()
        );
        ReflectionTestUtils.setField(snapshot, "id", snapshotId);
        return VerifiedSnapshot.from(snapshot, List.of());
    }

    public static VerifiedSnapshot create(
            Long snapshotId,
            Long eventId,
            int winnerCount,
            String drawMethod,
            String algorithmVersion,
            List<CandidateValue> candidates
    ) {
        DrawSnapshot snapshot = DrawSnapshot.create(
                eventId,
                winnerCount,
                drawMethod,
                algorithmVersion,
                "0".repeat(64),
                candidates
        );
        ReflectionTestUtils.setField(snapshot, "id", snapshotId);
        return VerifiedSnapshot.from(snapshot, candidates);
    }

    public static VerifiedSnapshot create(
            Long snapshotId,
            Long eventId,
            int winnerCount,
            String drawMethod,
            String algorithmVersion,
            List<CandidateValue> candidates,
            List<PrizeValue> prizes
    ) {
        DrawSnapshot snapshot = DrawSnapshot.create(
                eventId,
                winnerCount,
                drawMethod,
                algorithmVersion,
                "PRIZE_WEIGHTED_V1",
                "0".repeat(64),
                candidates,
                prizes
        );
        ReflectionTestUtils.setField(snapshot, "id", snapshotId);
        return VerifiedSnapshot.from(snapshot, candidates, prizes);
    }
}

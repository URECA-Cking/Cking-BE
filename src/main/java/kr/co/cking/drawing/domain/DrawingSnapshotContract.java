package kr.co.cking.drawing.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import kr.co.cking.snapshot.application.VerifiedSnapshot;

/** 무결성 검증을 마친 공식 Snapshot에서 INITIAL Drawing 생성에 필요한 확정 입력을 전달한다. */
public final class DrawingSnapshotContract {

    private final Long snapshotId;
    private final Long eventId;
    private final String drawMethod;
    private final String algorithmVersion;
    private final String prizeAlgorithmVersion;
    private final int winnerCount;

    private DrawingSnapshotContract(
            Long snapshotId,
            Long eventId,
            String drawMethod,
            String algorithmVersion,
            int winnerCount,
            String prizeAlgorithmVersion
    ) {
        requirePositive(snapshotId, "snapshotId");
        requirePositive(eventId, "eventId");
        if (drawMethod == null || drawMethod.isBlank()) {
            throw new IllegalArgumentException("drawMethod는 필수입니다.");
        }
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion은 필수입니다.");
        }
        if (winnerCount <= 0) {
            throw new IllegalArgumentException("winnerCount는 양수여야 합니다.");
        }
        this.snapshotId = snapshotId;
        this.eventId = eventId;
        this.drawMethod = drawMethod;
        this.algorithmVersion = algorithmVersion;
        this.winnerCount = winnerCount;
        if (prizeAlgorithmVersion == null || prizeAlgorithmVersion.isBlank()) {
            throw new IllegalArgumentException("prizeAlgorithmVersion은 필수입니다.");
        }
        this.prizeAlgorithmVersion = prizeAlgorithmVersion;
    }

    public static DrawingSnapshotContract from(VerifiedSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("검증된 Snapshot은 필수입니다.");
        }
        return new DrawingSnapshotContract(
                snapshot.snapshotId(),
                snapshot.eventId(),
                snapshot.drawMethod(),
                snapshot.algorithmVersion(),
                snapshot.winnerCount(),
                snapshot.prizeAlgorithmVersion()
        );
    }

    public Long snapshotId() {
        return snapshotId;
    }

    public Long eventId() {
        return eventId;
    }

    public String drawMethod() {
        return drawMethod;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }

    public int winnerCount() {
        return winnerCount;
    }

    public String prizeAlgorithmVersion() {
        return prizeAlgorithmVersion;
    }
}

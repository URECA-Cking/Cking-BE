package kr.co.cking.drawing.application;

import kr.co.cking.snapshot.application.VerifiedSnapshot;

/** INITIAL Drawing 실행 단계가 사용할, 관리자 권한과 Snapshot 무결성 검증을 마친 입력이다. */
public record InitialDrawingPreparation(VerifiedSnapshot verifiedSnapshot) {

    public InitialDrawingPreparation {
        if (verifiedSnapshot == null) {
            throw new IllegalArgumentException("검증된 Snapshot은 필수입니다.");
        }
    }

    static InitialDrawingPreparation from(VerifiedSnapshot snapshot) {
        return new InitialDrawingPreparation(snapshot);
    }

    public Long eventId() {
        return verifiedSnapshot.eventId();
    }

    public Long snapshotId() {
        return verifiedSnapshot.snapshotId();
    }

    public int winnerCount() {
        return verifiedSnapshot.winnerCount();
    }

    public String drawMethod() {
        return verifiedSnapshot.drawMethod();
    }

    public String algorithmVersion() {
        return verifiedSnapshot.algorithmVersion();
    }

    public int candidateCount() {
        return verifiedSnapshot.candidateCount();
    }
}

package kr.co.cking.drawing.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import kr.co.cking.drawing.domain.DrawingReplayedWinner;
import kr.co.cking.drawing.domain.DrawingVerificationHistory;
import kr.co.cking.drawing.domain.DrawingVerificationMode;
import kr.co.cking.drawing.domain.DrawingVerificationStatus;

public record DrawingVerificationResult(
        Long verificationId,
        Long drawingId,
        DrawingVerificationStatus status,
        DrawingVerificationMode verificationMode,
        Integer expectedWinnerCount,
        Integer actualWinnerCount,
        Boolean winnerCountMatched,
        Boolean winnersUnique,
        Boolean candidatesMatched,
        Boolean exclusionsMatched,
        Boolean ranksMatched,
        @Schema(description = "원본 Seed 재현 당첨자 순위 목록. 기존 이력이나 재현 전 실패는 null이며, 개인정보는 포함하지 않습니다.")
        List<DrawingReplayedWinner> replayedWinners,
        boolean snapshotHashMatched,
        boolean inputHashMatched,
        boolean resultHashMatched,
        boolean algorithmMatched,
        String failureCode,
        String failureMessage,
        Long verifiedBy,
        Instant verifiedAt
) {

    /** 저장된 검증 이력을 POST와 이력 조회가 공유하는 응답 형태로 변환한다. */
    public static DrawingVerificationResult from(DrawingVerificationHistory history) {
        return new DrawingVerificationResult(
                history.getId(),
                history.getDrawingId(),
                history.getStatus(),
                history.getVerificationMode(),
                history.getExpectedWinnerCount(),
                history.getActualWinnerCount(),
                history.getWinnerCountMatched(),
                history.getWinnersUnique(),
                history.getCandidatesMatched(),
                history.getExclusionsMatched(),
                history.getRanksMatched(),
                history.getReplayedWinners(),
                history.isSnapshotHashMatched(),
                history.isInputHashMatched(),
                history.isResultHashMatched(),
                history.isAlgorithmMatched(),
                history.getFailureCode(),
                history.getFailureMessage(),
                history.getVerifiedBy(),
                history.getVerifiedAt()
        );
    }
}

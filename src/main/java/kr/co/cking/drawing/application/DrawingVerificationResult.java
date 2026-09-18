package kr.co.cking.drawing.application;

import java.time.Instant;
import kr.co.cking.drawing.domain.DrawingVerificationHistory;
import kr.co.cking.drawing.domain.DrawingVerificationMode;
import kr.co.cking.drawing.domain.DrawingVerificationStatus;

public record DrawingVerificationResult(
        Long verificationId,
        Long drawingId,
        DrawingVerificationStatus status,
        DrawingVerificationMode verificationMode,
        int expectedWinnerCount,
        Integer actualWinnerCount,
        Boolean winnerCountMatched,
        Boolean winnersUnique,
        Boolean candidatesMatched,
        Boolean exclusionsMatched,
        Boolean ranksMatched,
        boolean snapshotHashMatched,
        boolean inputHashMatched,
        boolean resultHashMatched,
        boolean algorithmMatched,
        String failureCode,
        String failureMessage,
        Long verifiedBy,
        Instant verifiedAt
) {

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

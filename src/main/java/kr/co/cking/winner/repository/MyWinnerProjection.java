package kr.co.cking.winner.repository;

import java.time.Instant;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.winner.domain.WinnerManagementStatus;

/** 내 당첨 조회 응답을 만들기 위한 Winner·Drawing·WinnerManagement의 읽기 전용 결합 결과다. */
public record MyWinnerProjection(
        Long winnerId,
        Long eventId,
        Long drawingId,
        int drawNo,
        DrawingType drawType,
        int rankInDrawing,
        long appliedTicketCount,
        Instant winnerCreatedAt,
        Long winnerManagementId,
        WinnerManagementStatus winnerManagementStatus,
        Instant winnerManagementCreatedAt,
        Instant winnerManagementUpdatedAt
) {
}

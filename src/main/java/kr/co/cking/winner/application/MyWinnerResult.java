package kr.co.cking.winner.application;

import java.time.Instant;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.repository.MyWinnerProjection;

/** 당첨자 본인에게만 반환하는 Winner 불변 데이터와 현재 운영 상태다. */
public record MyWinnerResult(
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

    /** 읽기 전용 조회 결과를 외부 API 응답 형식으로 변환한다. */
    public static MyWinnerResult from(MyWinnerProjection projection) {
        return new MyWinnerResult(
                projection.winnerId(),
                projection.eventId(),
                projection.drawingId(),
                projection.drawNo(),
                projection.drawType(),
                projection.rankInDrawing(),
                projection.appliedTicketCount(),
                projection.winnerCreatedAt(),
                projection.winnerManagementId(),
                projection.winnerManagementStatus(),
                projection.winnerManagementCreatedAt(),
                projection.winnerManagementUpdatedAt()
        );
    }
}

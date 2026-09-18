package kr.co.cking.winner.repository;

import kr.co.cking.drawing.domain.DrawingType;

/** 공개 Winner 응답을 만들 때 필요한 Winner와 Drawing의 읽기 전용 결합 결과다. */
public record PublicWinnerProjection(
        Long winnerId,
        Long drawingId,
        int drawNo,
        DrawingType drawType,
        Long memberId,
        int rankInDrawing
) {
}

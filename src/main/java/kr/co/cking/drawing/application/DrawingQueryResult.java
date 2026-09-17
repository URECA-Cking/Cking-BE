package kr.co.cking.drawing.application;

import java.time.Instant;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;

public record DrawingQueryResult(
        Long drawingId,
        Long eventId,
        Long snapshotId,
        int drawNo,
        DrawingType drawType,
        DrawingStatus status,
        DrawingVisibility visibility,
        String drawMethod,
        String algorithmVersion,
        int winnerCount,
        Long requestedBy,
        Instant createdAt,
        Instant firstStartedAt,
        Instant completedAt,
        Instant publishedAt
) {

    public static DrawingQueryResult from(Drawing drawing) {
        return new DrawingQueryResult(
                drawing.getId(),
                drawing.getEventId(),
                drawing.getSnapshotId(),
                drawing.getDrawNo(),
                drawing.getDrawType(),
                drawing.getStatus(),
                drawing.getVisibility(),
                drawing.getDrawMethod(),
                drawing.getAlgorithmVersion(),
                drawing.getWinnerCount(),
                drawing.getRequestedBy(),
                drawing.getCreatedAt(),
                drawing.getFirstStartedAt(),
                drawing.getCompletedAt(),
                drawing.getPublishedAt()
        );
    }
}

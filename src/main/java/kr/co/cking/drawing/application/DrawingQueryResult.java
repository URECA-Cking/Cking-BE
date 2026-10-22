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
        String prizeAlgorithmVersion,
        int winnerCount,
        Long requestedBy,
        Instant createdAt,
        Instant firstStartedAt,
        Instant completedAt,
        Instant publishedAt
) {

    public DrawingQueryResult(Long drawingId, Long eventId, Long snapshotId, int drawNo, DrawingType drawType,
            DrawingStatus status, DrawingVisibility visibility, String drawMethod, String algorithmVersion,
            int winnerCount, Long requestedBy, Instant createdAt, Instant firstStartedAt, Instant completedAt,
            Instant publishedAt) {
        this(drawingId, eventId, snapshotId, drawNo, drawType, status, visibility, drawMethod, algorithmVersion,
                "PRIZE_WEIGHTED_V1", winnerCount, requestedBy, createdAt, firstStartedAt, completedAt, publishedAt);
    }

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
                drawing.getPrizeAlgorithmVersion(),
                drawing.getWinnerCount(),
                drawing.getRequestedBy(),
                drawing.getCreatedAt(),
                drawing.getFirstStartedAt(),
                drawing.getCompletedAt(),
                drawing.getPublishedAt()
        );
    }
}

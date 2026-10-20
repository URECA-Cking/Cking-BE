package kr.co.cking.event.application;

import java.time.Instant;
import java.util.List;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.event.application.dto.PrizeResult;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.SnapshotVerificationStatus;

/** 관리자 Event 운영 상세 화면이 초기 렌더링에 필요한 경량 정보만 담는다. */
public record AdminEventOperationResult(
        EventSummary event,
        SnapshotSummary snapshot,
        DrawingSummary initialDrawing
) {

    public static AdminEventOperationResult of(
            Event event,
            String creatorName,
            SnapshotSummary snapshot,
            DrawingSummary initialDrawing
    ) {
        return new AdminEventOperationResult(EventSummary.from(event, creatorName), snapshot, initialDrawing);
    }

    /** 상세 화면에 필요한 Event·Creator·상품 설정과 현재 lifecycle 상태다. */
    public record EventSummary(
            Long eventId,
            Long creatorId,
            String creatorName,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            String prizeAlgorithmVersion,
            EventStatus status,
            Instant closedAt,
            Instant publishedAt,
            Instant archivedAt,
            Long archivedBy,
            Instant createdAt,
            List<PrizeResult> prizes
    ) {
        static EventSummary from(Event event, String creatorName) {
            return new EventSummary(
                    event.getEventId(), event.getCreatorId(), creatorName, event.getTitle(), event.getDescription(),
                    event.getStartAt(), event.getEndAt(), event.getWinnerCount(),
                    DrawMethod.valueOf(event.getDrawMethod()), event.getPrizeAlgorithmVersion(), event.getStatus(),
                    event.getClosedAt(), event.getPublishedAt(), event.getArchivedAt(), event.getArchivedBy(),
                    event.getCreatedAt(), event.getPrizeConfigs().stream().map(PrizeResult::from).toList()
            );
        }
    }

    /** 후보 원본은 제외하고 Snapshot 식별·검증 상태와 집계만 반환한다. */
    public record SnapshotSummary(
            Long snapshotId,
            SnapshotVerificationStatus verificationStatus,
            int candidateCount,
            long totalTicketCount,
            int winnerCount,
            Instant createdAt
    ) {
        static SnapshotSummary from(DrawSnapshot snapshot) {
            return new SnapshotSummary(
                    snapshot.getId(), snapshot.getVerificationStatus(), snapshot.getCandidateCount(),
                    snapshot.getTotalTicketCount(), snapshot.getWinnerCount(), snapshot.getCreatedAt()
            );
        }
    }

    /** INITIAL Drawing과 결과 존재 여부를 요약하며 Winner 전체는 반환하지 않는다. */
    public record DrawingSummary(
            Long drawingId,
            int drawNo,
            DrawingType drawType,
            DrawingStatus status,
            DrawingVisibility visibility,
            int winnerCount,
            long storedWinnerCount,
            Instant createdAt,
            Instant completedAt,
            Instant publishedAt
    ) {
        static DrawingSummary from(Drawing drawing, long storedWinnerCount) {
            return new DrawingSummary(
                    drawing.getId(), drawing.getDrawNo(), drawing.getDrawType(), drawing.getStatus(),
                    drawing.getVisibility(), drawing.getWinnerCount(), storedWinnerCount, drawing.getCreatedAt(),
                    drawing.getCompletedAt(), drawing.getPublishedAt()
            );
        }
    }
}

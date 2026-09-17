package kr.co.cking.drawing.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(
        name = "drawing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_drawing_event_no", columnNames = {"event_id", "draw_no"}),
                @UniqueConstraint(name = "uk_drawing_seed", columnNames = "seed_id"),
                @UniqueConstraint(name = "uk_drawing_redraw_req", columnNames = "redraw_request_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Drawing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "draw_no", nullable = false, updatable = false)
    private int drawNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "draw_type", nullable = false, updatable = false, length = 30)
    private DrawingType drawType;

    @Column(name = "original_drawing_id", updatable = false)
    private Long originalDrawingId;

    @Column(name = "redraw_request_id", updatable = false)
    private Long redrawRequestId;

    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private Long snapshotId;

    @Column(name = "seed_id", nullable = false, updatable = false)
    private Long seedId;

    @Column(name = "draw_method", nullable = false, updatable = false, length = 30)
    private String drawMethod;

    @Column(name = "algorithm_version", nullable = false, updatable = false, length = 30)
    private String algorithmVersion;

    @Column(name = "winner_count", nullable = false, updatable = false)
    private int winnerCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DrawingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 30)
    private DrawingVisibility visibility;

    @Column(name = "input_payload", columnDefinition = "TEXT")
    private String inputPayload;

    @Column(name = "output_payload", columnDefinition = "TEXT")
    private String outputPayload;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "result_hash", length = 64)
    private String resultHash;

    @Column(name = "requested_by", updatable = false)
    private Long requestedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "first_started_at")
    private Instant firstStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static Drawing createInitial(
            Long eventId,
            Long snapshotId,
            Long seedId,
            String drawMethod,
            String algorithmVersion,
            int winnerCount,
            Long requestedBy
    ) {
        requirePositive(eventId, "eventId");
        requirePositive(snapshotId, "snapshotId");
        requirePositive(seedId, "seedId");
        requirePositive(requestedBy, "requestedBy");
        requireText(drawMethod, "drawMethod");
        requireText(algorithmVersion, "algorithmVersion");
        if (winnerCount <= 0) {
            throw new IllegalArgumentException("winnerCount는 양수여야 합니다.");
        }

        Drawing drawing = new Drawing();
        drawing.eventId = eventId;
        drawing.drawNo = 0;
        drawing.drawType = DrawingType.INITIAL;
        drawing.snapshotId = snapshotId;
        drawing.seedId = seedId;
        drawing.drawMethod = drawMethod;
        drawing.algorithmVersion = algorithmVersion;
        drawing.winnerCount = winnerCount;
        drawing.status = DrawingStatus.READY;
        drawing.visibility = DrawingVisibility.PRIVATE;
        drawing.requestedBy = requestedBy;
        drawing.attemptCount = 0;
        return drawing;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 필수입니다.");
        }
    }
}

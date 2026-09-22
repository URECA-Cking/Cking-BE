package kr.co.cking.drawing.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import kr.co.cking.common.exception.BusinessException;

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
                @UniqueConstraint(name = "uk_drawing_redraw_req", columnNames = "redraw_request_id"),
                @UniqueConstraint(name = "uk_drawing_id_event", columnNames = {"id", "event_id"})
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

    @Column(name = "prize_algorithm_version", nullable = false, updatable = false, length = 30)
    private String prizeAlgorithmVersion;

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
            DrawingSnapshotContract snapshot,
            Long seedId,
            Long requestedBy
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException("검증된 Snapshot 계약은 필수입니다.");
        }
        requirePositive(seedId, "seedId");
        requirePositive(requestedBy, "requestedBy");

        Drawing drawing = new Drawing();
        drawing.eventId = snapshot.eventId();
        drawing.drawNo = 0;
        drawing.drawType = DrawingType.INITIAL;
        drawing.snapshotId = snapshot.snapshotId();
        drawing.seedId = seedId;
        drawing.drawMethod = snapshot.drawMethod();
        drawing.algorithmVersion = snapshot.algorithmVersion();
        drawing.prizeAlgorithmVersion = snapshot.prizeAlgorithmVersion();
        drawing.winnerCount = snapshot.winnerCount();
        drawing.status = DrawingStatus.READY;
        drawing.visibility = DrawingVisibility.PRIVATE;
        drawing.requestedBy = requestedBy;
        drawing.attemptCount = 0;
        return drawing;
    }

    /** INITIAL Drawing의 검증된 입력 규격을 복제해 결원 수만 다른 REDRAW Drawing을 만든다. */
    public static Drawing createRedraw(Drawing initial, int drawNo, Long redrawRequestId, Long seedId, int vacancyCount,
            Long requestedBy) {
        if (initial == null || initial.drawType != DrawingType.INITIAL || drawNo <= 0 || redrawRequestId == null
                || seedId == null || vacancyCount <= 0 || requestedBy == null) {
            throw new IllegalArgumentException("REDRAW Drawing 생성 입력이 올바르지 않습니다.");
        }
        Drawing drawing = new Drawing();
        drawing.eventId = initial.eventId;
        drawing.drawNo = drawNo;
        drawing.drawType = DrawingType.REDRAW;
        drawing.originalDrawingId = initial.id;
        drawing.redrawRequestId = redrawRequestId;
        drawing.snapshotId = initial.snapshotId;
        drawing.seedId = seedId;
        drawing.drawMethod = initial.drawMethod;
        drawing.algorithmVersion = initial.algorithmVersion;
        drawing.prizeAlgorithmVersion = initial.prizeAlgorithmVersion;
        drawing.winnerCount = vacancyCount;
        drawing.status = DrawingStatus.READY;
        drawing.visibility = DrawingVisibility.PRIVATE;
        drawing.requestedBy = requestedBy;
        drawing.attemptCount = 0;
        return drawing;
    }

    /** 확정 입력과 함께 최초 실행을 시작한다. */
    public void start(String canonicalInput, String inputHash, Instant startedAt) {
        if (status != DrawingStatus.READY) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }
        requireText(canonicalInput, "canonicalInput");
        requireSha256(inputHash, "inputHash");
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt은 필수입니다.");
        }

        this.inputPayload = canonicalInput;
        this.inputHash = inputHash;
        this.status = DrawingStatus.RUNNING;
        this.firstStartedAt = startedAt;
        this.attemptCount++;
    }

    /** 추첨 결과 Hash와 Payload를 저장하고 실행을 완료한다. */
    public void complete(String canonicalOutput, String resultHash, Instant completedAt) {
        if (status != DrawingStatus.RUNNING) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }
        requireText(canonicalOutput, "canonicalOutput");
        requireSha256(resultHash, "resultHash");
        if (completedAt == null) {
            throw new IllegalArgumentException("completedAt은 필수입니다.");
        }

        this.outputPayload = canonicalOutput;
        this.resultHash = resultHash;
        this.status = DrawingStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    /**
     * 완료된 추첨의 결과를 공개한다. 이미 공개된 경우 상태를 바꾸지 않고 조용히 반환한다(취합v1.5.4
     * §12: 동일 공개 요청 중복 실행 시 알림 중복 생성 0건 — 호출부가 이 멱등성을 근거로 삼는다).
     */
    public void publish(Instant now) {
        if (visibility == DrawingVisibility.PUBLIC) {
            return;
        }
        if (status != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        }
        visibility = DrawingVisibility.PUBLIC;
        publishedAt = now;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 필수입니다.");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + "는 SHA-256 lowercase hex여야 합니다.");
        }
    }
}

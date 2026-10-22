package kr.co.cking.drawing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 완료된 Drawing을 변경하지 않고 수행한 검증 결과를 append-only로 보존한다. */
@Getter
@Entity
@Table(name = "draw_verification_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawingVerificationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "drawing_id", nullable = false, updatable = false)
    private Long drawingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 30)
    private DrawingVerificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_mode", nullable = false, updatable = false, length = 50)
    private DrawingVerificationMode verificationMode;

    @Column(name = "replay_seed_value", updatable = false, length = 32)
    private byte[] replaySeedValue;

    @Column(name = "expected_winner_count", updatable = false)
    private Integer expectedWinnerCount;

    @Column(name = "actual_winner_count", updatable = false)
    private Integer actualWinnerCount;

    @Column(name = "winner_count_matched", updatable = false)
    private Boolean winnerCountMatched;

    @Column(name = "winners_unique", updatable = false)
    private Boolean winnersUnique;

    @Column(name = "candidates_matched", updatable = false)
    private Boolean candidatesMatched;

    @Column(name = "exclusions_matched", updatable = false)
    private Boolean exclusionsMatched;

    @Column(name = "ranks_matched", updatable = false)
    private Boolean ranksMatched;

    @Column(name = "snapshot_hash_matched", nullable = false, updatable = false)
    private boolean snapshotHashMatched;

    @Column(name = "input_hash_matched", nullable = false, updatable = false)
    private boolean inputHashMatched;

    @Column(name = "result_hash_matched", nullable = false, updatable = false)
    private boolean resultHashMatched;

    @Column(name = "algorithm_matched", nullable = false, updatable = false)
    private boolean algorithmMatched;

    @Column(name = "failure_code", updatable = false, length = 50)
    private String failureCode;

    @Column(name = "failure_message", updatable = false, columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "verified_by", nullable = false, updatable = false)
    private Long verifiedBy;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt;

    public static DrawingVerificationHistory record(
            Long drawingId,
            DrawingVerificationStatus status,
            byte[] replaySeedValue,
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
        if (drawingId == null || drawingId <= 0 || verifiedBy == null || verifiedBy <= 0) {
            throw new IllegalArgumentException("drawingId와 verifiedBy는 양수여야 합니다.");
        }
        if (status == null || expectedWinnerCount <= 0 || verifiedAt == null) {
            throw new IllegalArgumentException("검증 상태, 기대 당첨자 수, 검증 시각은 필수입니다.");
        }
        if (replaySeedValue != null && replaySeedValue.length != 32) {
            throw new IllegalArgumentException("재실행 Seed는 32바이트여야 합니다.");
        }

        DrawingVerificationHistory history = new DrawingVerificationHistory();
        history.drawingId = drawingId;
        history.status = status;
        history.verificationMode = DrawingVerificationMode.DETERMINISTIC_AND_CARDINALITY_REPLAY;
        history.replaySeedValue = replaySeedValue == null ? null : Arrays.copyOf(replaySeedValue, replaySeedValue.length);
        history.expectedWinnerCount = expectedWinnerCount;
        history.actualWinnerCount = actualWinnerCount;
        history.winnerCountMatched = winnerCountMatched;
        history.winnersUnique = winnersUnique;
        history.candidatesMatched = candidatesMatched;
        history.exclusionsMatched = exclusionsMatched;
        history.ranksMatched = ranksMatched;
        history.snapshotHashMatched = snapshotHashMatched;
        history.inputHashMatched = inputHashMatched;
        history.resultHashMatched = resultHashMatched;
        history.algorithmMatched = algorithmMatched;
        history.failureCode = failureCode;
        history.failureMessage = failureMessage;
        history.verifiedBy = verifiedBy;
        history.verifiedAt = verifiedAt;
        return history;
    }

    public byte[] getReplaySeedValue() {
        return replaySeedValue == null ? null : Arrays.copyOf(replaySeedValue, replaySeedValue.length);
    }
}

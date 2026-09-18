package kr.co.cking.winner.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import kr.co.cking.snapshot.domain.PrizeValue;

@Getter
@Entity
@Table(
        name = "winner",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_winner_drawing_rank", columnNames = {"drawing_id", "rank_in_drawing"}),
                @UniqueConstraint(name = "uk_winner_event_member", columnNames = {"event_id", "member_id"})
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Winner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "drawing_id", nullable = false, updatable = false)
    private Long drawingId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "rank_in_drawing", nullable = false, updatable = false)
    private int rankInDrawing;

    @Column(name = "applied_ticket_count", nullable = false, updatable = false)
    private long appliedTicketCount;

    @Column(name = "snapshot_prize_id", updatable = false)
    private Long snapshotPrizeId;

    @Column(name = "prize_key", updatable = false, length = 100)
    private String prizeKey;

    @Column(name = "prize_display_name", updatable = false, length = 200)
    private String prizeDisplayName;

    @Column(name = "prize_priority", updatable = false)
    private Integer prizePriority;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Winner create(
            Long eventId,
            Long drawingId,
            Long memberId,
            int rankInDrawing,
            long appliedTicketCount
    ) {
        return create(eventId, drawingId, memberId, rankInDrawing, appliedTicketCount, null);
    }

    public static Winner create(
            Long eventId,
            Long drawingId,
            Long memberId,
            int rankInDrawing,
            long appliedTicketCount,
            PrizeValue prize
    ) {
        requirePositive(eventId, "eventId");
        requirePositive(drawingId, "drawingId");
        requirePositive(memberId, "memberId");
        if (rankInDrawing <= 0) {
            throw new IllegalArgumentException("rankInDrawing은 양수여야 합니다.");
        }
        if (appliedTicketCount <= 0) {
            throw new IllegalArgumentException("appliedTicketCount는 양수여야 합니다.");
        }

        Winner winner = new Winner();
        winner.eventId = eventId;
        winner.drawingId = drawingId;
        winner.memberId = memberId;
        winner.rankInDrawing = rankInDrawing;
        winner.appliedTicketCount = appliedTicketCount;
        if (prize != null) {
            requirePositive(prize.snapshotPrizeId(), "snapshotPrizeId");
            winner.snapshotPrizeId = prize.snapshotPrizeId();
            winner.prizeKey = prize.prizeKey();
            winner.prizeDisplayName = prize.displayName();
            winner.prizePriority = prize.priority();
        }
        return winner;
    }

}

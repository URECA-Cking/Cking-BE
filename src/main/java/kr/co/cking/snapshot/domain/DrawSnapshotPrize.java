package kr.co.cking.snapshot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** Event 마감 시 복제되어 이후 수정되지 않는 공식 상품 등급 Snapshot이다. */
@Getter
@Entity
@Table(name = "draw_snapshot_prize", uniqueConstraints = @UniqueConstraint(
        name = "uk_snapshot_prize_key", columnNames = {"snapshot_id", "prize_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawSnapshotPrize {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private DrawSnapshot snapshot;

    @Column(name = "prize_key", nullable = false, updatable = false, length = 100)
    private String prizeKey;
    @Column(name = "display_name", nullable = false, updatable = false, length = 200)
    private String displayName;
    @Column(name = "priority", nullable = false, updatable = false)
    private int priority;
    @Column(name = "probability_weight", nullable = false, updatable = false)
    private long weight;
    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    DrawSnapshotPrize(DrawSnapshot snapshot, PrizeValue value) {
        if (snapshot == null || value == null) {
            throw new IllegalArgumentException("Snapshot과 상품 값은 필수입니다.");
        }
        this.snapshot = snapshot;
        this.prizeKey = value.prizeKey();
        this.displayName = value.displayName();
        this.priority = value.priority();
        this.weight = value.weight();
        this.quantity = value.quantity();
    }

    public PrizeValue toValue() {
        return new PrizeValue(id, prizeKey, displayName, priority, weight, quantity);
    }
}

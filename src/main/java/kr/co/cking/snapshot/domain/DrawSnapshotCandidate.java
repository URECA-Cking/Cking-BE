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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(name = "draw_snapshot_candidate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawSnapshotCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private DrawSnapshot snapshot;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "ticket_count", nullable = false, updatable = false)
    private long ticketCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    DrawSnapshotCandidate(DrawSnapshot snapshot, Long memberId, long ticketCount) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot은 필수입니다.");
        }
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
        if (ticketCount <= 0) {
            throw new IllegalArgumentException("ticketCount는 양수여야 합니다.");
        }

        this.snapshot = snapshot;
        this.memberId = memberId;
        this.ticketCount = ticketCount;
    }
}

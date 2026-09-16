package kr.co.cking.snapshot.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(name = "draw_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "candidate_count", nullable = false, updatable = false)
    private int candidateCount;

    @Column(name = "total_ticket_count", nullable = false, updatable = false)
    private long totalTicketCount;

    @Column(name = "winner_count", nullable = false, updatable = false)
    private int winnerCount;

    @Column(name = "draw_method", nullable = false, updatable = false, length = 30)
    private String drawMethod;

    @Column(name = "algorithm_version", nullable = false, updatable = false, length = 30)
    private String algorithmVersion;

    @Column(name = "snapshot_hash", nullable = false, updatable = false, length = 64)
    private String snapshotHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 30)
    private SnapshotVerificationStatus verificationStatus;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL)
    @OrderBy("memberId ASC")
    private List<DrawSnapshotCandidate> candidates = new ArrayList<>();

    public static DrawSnapshot create(
            Long eventId,
            int winnerCount,
            String drawMethod,
            String algorithmVersion,
            String snapshotHash,
            List<CandidateValue> candidateValues
    ) {
        if (eventId == null || eventId <= 0) {
            throw new IllegalArgumentException("eventId는 양수여야 합니다.");
        }
        if (winnerCount <= 0) {
            throw new IllegalArgumentException("winnerCount는 양수여야 합니다.");
        }
        if (drawMethod == null || drawMethod.isBlank()) {
            throw new IllegalArgumentException("drawMethod는 필수입니다.");
        }
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion은 필수입니다.");
        }
        if (snapshotHash == null || !snapshotHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotHash는 SHA-256 lowercase hex여야 합니다.");
        }

        List<CandidateValue> sortedCandidates = candidateValues.stream()
                .sorted(CandidateValue.BY_MEMBER_ID)
                .toList();

        DrawSnapshot snapshot = new DrawSnapshot();
        snapshot.eventId = eventId;
        snapshot.candidateCount = sortedCandidates.size();
        snapshot.totalTicketCount = sortedCandidates.stream()
                .mapToLong(CandidateValue::ticketCount)
                .sum();
        snapshot.winnerCount = winnerCount;
        snapshot.drawMethod = drawMethod;
        snapshot.algorithmVersion = algorithmVersion;
        snapshot.snapshotHash = snapshotHash;
        snapshot.verificationStatus = SnapshotVerificationStatus.UNVERIFIED;
        snapshot.candidates = sortedCandidates.stream()
                .map(candidate -> new DrawSnapshotCandidate(
                        snapshot,
                        candidate.memberId(),
                        candidate.ticketCount()
                ))
                .collect(Collectors.toCollection(ArrayList::new));
        return snapshot;
    }

    public List<DrawSnapshotCandidate> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }
}

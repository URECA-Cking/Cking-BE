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
import jakarta.persistence.UniqueConstraint;
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
@Table(
        name = "draw_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_snapshot_drawing_contract",
                columnNames = {"id", "event_id", "draw_method", "algorithm_version"}
        )
)
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

    @Column(name = "prize_algorithm_version", nullable = false, updatable = false, length = 30)
    private String prizeAlgorithmVersion;

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

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL)
    @OrderBy("priority ASC, prizeKey ASC")
    private List<DrawSnapshotPrize> prizes = new ArrayList<>();

    public static DrawSnapshot create(
            Long eventId,
            int winnerCount,
            String drawMethod,
            String algorithmVersion,
            String snapshotHash,
            List<CandidateValue> candidateValues
    ) {
        return create(eventId, winnerCount, drawMethod, algorithmVersion, "PRIZE_WEIGHTED_V1", snapshotHash,
                candidateValues, List.of());
    }

    public static DrawSnapshot create(
            Long eventId,
            int winnerCount,
            String drawMethod,
            String algorithmVersion,
            String prizeAlgorithmVersion,
            String snapshotHash,
            List<CandidateValue> candidateValues,
            List<PrizeValue> prizeValues
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
        if (prizeAlgorithmVersion == null || prizeAlgorithmVersion.isBlank()) {
            throw new IllegalArgumentException("prizeAlgorithmVersion은 필수입니다.");
        }
        if (snapshotHash == null || !snapshotHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotHash는 SHA-256 lowercase hex여야 합니다.");
        }

        List<CandidateValue> normalizedCandidates = List.copyOf(candidateValues);
        validateCandidateOrder(normalizedCandidates);
        List<PrizeValue> normalizedPrizes = normalizePrizes(prizeValues, winnerCount);

        DrawSnapshot snapshot = new DrawSnapshot();
        snapshot.eventId = eventId;
        snapshot.candidateCount = normalizedCandidates.size();
        snapshot.totalTicketCount = normalizedCandidates.stream()
                .mapToLong(CandidateValue::ticketCount)
                .sum();
        snapshot.winnerCount = winnerCount;
        snapshot.drawMethod = drawMethod;
        snapshot.algorithmVersion = algorithmVersion;
        snapshot.prizeAlgorithmVersion = prizeAlgorithmVersion;
        snapshot.snapshotHash = snapshotHash;
        snapshot.verificationStatus = SnapshotVerificationStatus.UNVERIFIED;
        snapshot.candidates = normalizedCandidates.stream()
                .map(candidate -> new DrawSnapshotCandidate(
                        snapshot,
                        candidate.memberId(),
                        candidate.ticketCount()
                ))
                .collect(Collectors.toCollection(ArrayList::new));
        snapshot.prizes = normalizedPrizes.stream()
                .map(prize -> new DrawSnapshotPrize(snapshot, prize))
                .collect(Collectors.toCollection(ArrayList::new));
        return snapshot;
    }

    private static List<PrizeValue> normalizePrizes(List<PrizeValue> prizes, int winnerCount) {
        if (prizes == null) {
            throw new IllegalArgumentException("상품 설정은 필수입니다.");
        }
        List<PrizeValue> normalized = prizes.stream()
                .sorted(java.util.Comparator.comparingInt(PrizeValue::priority).thenComparing(PrizeValue::prizeKey))
                .toList();
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        long totalQuantity = 0;
        long totalWeight = 0;
        for (PrizeValue prize : normalized) {
            if (prize == null || !keys.add(prize.prizeKey())) {
                throw new IllegalArgumentException("상품 식별자는 중복될 수 없습니다.");
            }
            totalQuantity = Math.addExact(totalQuantity, prize.quantity());
            totalWeight = Math.addExact(totalWeight, prize.weight());
        }
        if (!normalized.isEmpty() && totalQuantity < winnerCount) {
            throw new IllegalArgumentException("총 상품 수량은 winnerCount 이상이어야 합니다.");
        }
        return normalized;
    }

    private static void validateCandidateOrder(List<CandidateValue> candidates) {
        for (int index = 1; index < candidates.size(); index++) {
            long previousMemberId = candidates.get(index - 1).memberId();
            long currentMemberId = candidates.get(index).memberId();
            if (previousMemberId >= currentMemberId) {
                throw new IllegalArgumentException("Snapshot 후보는 memberId ASC로 정렬되고 중복이 없어야 합니다.");
            }
        }
    }

    public List<DrawSnapshotCandidate> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public List<DrawSnapshotPrize> getPrizes() {
        return Collections.unmodifiableList(prizes);
    }
}

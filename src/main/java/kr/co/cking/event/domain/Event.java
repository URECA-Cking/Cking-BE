package kr.co.cking.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    private static final Duration MINIMUM_APPROVAL_REQUEST_LEAD_TIME = Duration.ofHours(48);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;

    private Long creatorId;
    private String requestId;
    private String title;
    private String description;
    private Instant startAt;
    private Instant endAt;
    private Integer winnerCount;
    private String drawMethod;
    @Column(name = "prize_algorithm_version", nullable = false, length = 30)
    private String prizeAlgorithmVersion;

    @Enumerated(EnumType.STRING)
    private EventStatus status;

    private String cutoffStreamId;
    private Instant closedAt;
    private Instant publishedAt;
    private Instant deletedAt;
    private Instant archivedAt;
    private Long archivedBy;
    private Long createdBy;

    @Column(updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("priority ASC, prizeKey ASC")
    private List<EventPrize> prizes = new ArrayList<>();

    @Builder
    private Event(
            Long creatorId,
            String requestId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            Integer winnerCount,
            String drawMethod,
            String prizeAlgorithmVersion,
            EventStatus status,
            Long createdBy,
            Instant createdAt
    ) {
        this.creatorId = creatorId;
        this.requestId = requestId;
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod;
        this.prizeAlgorithmVersion = prizeAlgorithmVersion == null
                ? PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1.name() : prizeAlgorithmVersion;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** Creator가 작성한 새 Event를 초안 상태로 생성한다. */
    public Event(
            Long creatorId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            Long createdBy,
            String requestId
    ) {
        this(creatorId, title, description, startAt, endAt, winnerCount, drawMethod, createdBy, requestId, List.of(),
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1);
    }

    public Event(
            Long creatorId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            Long createdBy,
            String requestId,
            List<PrizeConfig> prizes
    ) {
        this(creatorId, title, description, startAt, endAt, winnerCount, drawMethod, createdBy, requestId, prizes,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1);
    }

    public Event(
            Long creatorId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            Long createdBy,
            String requestId,
            List<PrizeConfig> prizes,
            PrizeAllocationAlgorithmVersion prizeAlgorithmVersion
    ) {
        if (prizeAlgorithmVersion == null) {
            throw new IllegalArgumentException("상품 배정 알고리즘은 필수입니다.");
        }
        this.creatorId = creatorId;
        this.requestId = requestId;
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod.name();
        this.prizeAlgorithmVersion = prizeAlgorithmVersion.name();
        this.status = EventStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        replacePrizes(prizes);
    }

    public void requestApproval() {
        requireStatus(EventStatus.DRAFT);
        status = EventStatus.PENDING_APPROVAL;
    }

    /** 승인 요청은 Event 시작 시각의 최소 48시간 전에만 허용한다. */
    public void validateApprovalRequestLeadTime(Instant now) {
        requireStatus(EventStatus.DRAFT);
        if (startAt.isBefore(now.plus(MINIMUM_APPROVAL_REQUEST_LEAD_TIME))) {
            throw new BusinessException(EventErrorCode.APPROVAL_REQUEST_TOO_LATE);
        }
    }

    public void approve() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.SCHEDULED;
    }

    public void open() {
        requireStatus(EventStatus.SCHEDULED);
        status = EventStatus.OPEN;
    }

    /** 마감된 Event를 초기 추첨 완료 상태로 전이한다. */
    public void completeDrawing() {
        requireStatus(EventStatus.CLOSED);
        status = EventStatus.DRAW_COMPLETED;
    }

    /** 추첨이 완료된 Event를 결과 공개 상태로 전이한다. */
    public void publish(Instant publishedAt) {
        requireStatus(EventStatus.DRAW_COMPLETED);
        status = EventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    public void reject() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.REJECTED;
    }

    public void changeToDraft() {
        requireStatus(EventStatus.REJECTED);
        status = EventStatus.DRAFT;
    }

    public void update(
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod
    ) {
        update(title, description, startAt, endAt, winnerCount, drawMethod, getPrizeConfigs(),
                PrizeAllocationAlgorithmVersion.from(prizeAlgorithmVersion));
    }

    public void update(
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            List<PrizeConfig> prizes
    ) {
        update(title, description, startAt, endAt, winnerCount, drawMethod, prizes,
                PrizeAllocationAlgorithmVersion.from(prizeAlgorithmVersion));
    }

    public void update(
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            List<PrizeConfig> prizes,
            PrizeAllocationAlgorithmVersion prizeAlgorithmVersion
    ) {
        requireStatus(EventStatus.DRAFT);
        if (prizeAlgorithmVersion == null) {
            throw new IllegalArgumentException("상품 배정 알고리즘은 필수입니다.");
        }
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod.name();
        this.prizeAlgorithmVersion = prizeAlgorithmVersion.name();
        replacePrizes(prizes);
    }

    public List<EventPrize> getPrizes() {
        return Collections.unmodifiableList(prizes);
    }

    public List<PrizeConfig> getPrizeConfigs() {
        return prizes.stream().map(EventPrize::toConfig).toList();
    }

    private void replacePrizes(List<PrizeConfig> prizeConfigs) {
        if (prizeConfigs == null) {
            throw new IllegalArgumentException("상품 설정은 필수입니다.");
        }
        Set<String> prizeKeys = new HashSet<>();
        long totalQuantity = 0;
        long totalWeight = 0;
        for (PrizeConfig config : prizeConfigs) {
            if (config == null || !prizeKeys.add(config.prizeKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("상품 식별자는 중복될 수 없습니다.");
            }
            totalQuantity = Math.addExact(totalQuantity, config.quantity());
            totalWeight = Math.addExact(totalWeight, config.weight());
        }
        if (!prizeConfigs.isEmpty() && totalQuantity < winnerCount) {
            throw new IllegalArgumentException("총 상품 수량은 winnerCount 이상이어야 합니다.");
        }
        // V10의 대소문자를 구분하지 않는 유니크 키와 같은 기준으로 기존 상품을 찾는다.
        Map<String, EventPrize> existing = new HashMap<>();
        prizes.forEach(prize -> existing.put(prize.getPrizeKey().toLowerCase(Locale.ROOT), prize));
        prizes.removeIf(prize -> !prizeKeys.contains(prize.getPrizeKey().toLowerCase(Locale.ROOT)));
        for (PrizeConfig config : prizeConfigs) {
            EventPrize prize = existing.get(config.prizeKey().toLowerCase(Locale.ROOT));
            if (prize == null) {
                prizes.add(new EventPrize(this, config));
            } else {
                prize.update(config);
            }
        }
        prizes.sort(java.util.Comparator.comparingInt(EventPrize::getPriority).thenComparing(EventPrize::getPrizeKey));
    }

    public void delete(Instant deletedAt) {
        if (status != EventStatus.DRAFT && status != EventStatus.REJECTED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        this.deletedAt = deletedAt;
    }

    /** 공개가 끝난 Event의 업무 원본은 보존한 채 사용자 노출만 중단한다. */
    public void archive(Instant archivedAt, Long archivedBy) {
        requireStatus(EventStatus.PUBLISHED);
        if (this.archivedAt != null) {
            return;
        }
        this.archivedAt = archivedAt;
        this.archivedBy = archivedBy;
    }

    /** 보관 Event를 기존 공개 상태 그대로 다시 노출한다. */
    public void unarchive() {
        requireStatus(EventStatus.PUBLISHED);
        this.archivedAt = null;
        this.archivedBy = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public DisplayStatus displayStatus(Instant now) {
        return DisplayStatus.of(status, endAt, now);
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Gate 차단·cutoff 확정 이후 호출. OPEN인 이벤트만 CLOSING으로 전이한다. */
    public void startClosing(String cutoffStreamId) {
        requireStatus(EventStatus.OPEN);
        this.cutoffStreamId = cutoffStreamId;
        this.status = EventStatus.CLOSING;
    }

    /** Drain(미처리 응모 반영) 완료 확인 후 호출. CLOSING인 이벤트만 CLOSED로 전이한다. */
    public void completeClosing(Instant closedAt) {
        requireStatus(EventStatus.CLOSING);
        this.status = EventStatus.CLOSED;
        this.closedAt = closedAt;
    }

    private void requireStatus(EventStatus expected) {
        if (status != expected) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }
}

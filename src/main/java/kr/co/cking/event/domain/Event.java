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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

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
    public void publish() {
        requireStatus(EventStatus.DRAW_COMPLETED);
        status = EventStatus.PUBLISHED;
        publishedAt = Instant.now();
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
            if (config == null || !prizeKeys.add(config.prizeKey())) {
                throw new IllegalArgumentException("상품 식별자는 중복될 수 없습니다.");
            }
            totalQuantity = Math.addExact(totalQuantity, config.quantity());
            totalWeight = Math.addExact(totalWeight, config.weight());
        }
        if (!prizeConfigs.isEmpty() && totalQuantity < winnerCount) {
            throw new IllegalArgumentException("총 상품 수량은 winnerCount 이상이어야 합니다.");
        }
        prizes.clear();
        prizeConfigs.stream()
                .sorted(java.util.Comparator.comparingInt(PrizeConfig::priority).thenComparing(PrizeConfig::prizeKey))
                .map(config -> new EventPrize(this, config))
                .forEach(prizes::add);
    }

    public void delete() {
        if (status != EventStatus.DRAFT && status != EventStatus.REJECTED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        deletedAt = Instant.now();
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

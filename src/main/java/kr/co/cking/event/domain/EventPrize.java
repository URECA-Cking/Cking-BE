package kr.co.cking.event.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DRAFT Event에 설정한 상품 등급과 확률 가중치, 재고 상한을 보존한다. */
@Getter
@Entity
@Table(
        name = "event_prize",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_prize_key", columnNames = {"event_id", "prize_key"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventPrize {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private Event event;

    @Column(name = "prize_key", nullable = false, length = 100)
    private String prizeKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "probability_weight", nullable = false)
    private long weight;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    EventPrize(Event event, PrizeConfig config) {
        if (event == null || config == null) {
            throw new IllegalArgumentException("Event와 상품 설정은 필수입니다.");
        }
        this.event = event;
        this.prizeKey = config.prizeKey();
        this.displayName = config.displayName().trim();
        this.priority = config.priority();
        this.weight = config.weight();
        this.quantity = config.quantity();
    }

    public PrizeConfig toConfig() {
        return new PrizeConfig(prizeKey, displayName, priority, weight, quantity);
    }
}

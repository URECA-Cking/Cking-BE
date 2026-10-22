package kr.co.cking.ticket.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import kr.co.cking.ticket.application.TicketBalanceQueryService;

/**
 * {@link TicketBalanceQueryService}의 조회와 EARN/SPEND Consumer의 갱신에 쓰인다.
 * {@code updated_at}은 {@link #applyDelta}가 명시적으로 채운다 — JPA UPDATE가 항상
 * 그 컬럼을 SET 절에 포함하므로 DB {@code ON UPDATE CURRENT_TIMESTAMP}는 무시된다.
 */
@Entity
@Table(name = "user_ticket_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTicketBalance {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private UserTicketBalanceId id;

    private Long balance;

    private Instant updatedAt;

    @Builder
    private UserTicketBalance(Long memberId, Long creatorId, Long balance, Instant updatedAt) {
        this.id = new UserTicketBalanceId(memberId, creatorId);
        this.balance = balance;
        this.updatedAt = updatedAt;
    }

    public Long getMemberId() {
        return id.getMemberId();
    }

    public Long getCreatorId() {
        return id.getCreatorId();
    }

    public void applyDelta(long delta, Instant updatedAt) {
        this.balance += delta;
        this.updatedAt = updatedAt;
    }
}

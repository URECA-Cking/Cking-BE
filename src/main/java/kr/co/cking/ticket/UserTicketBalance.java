package kr.co.cking.ticket;

import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 지금은 {@link TicketBalanceQueryService}의 읽기 전용 조회에만 쓰인다. EARN/SPEND
 * Consumer(T2-04)가 이 Entity로 갱신하게 되면 {@code updated_at}의 저장 방식(자동 채움
 * 등)을 그때 다시 봐야 한다 — DB에 {@code ON UPDATE CURRENT_TIMESTAMP}가 있어 지금
 * 매핑만 해둬도 조회 결과는 정확하다.
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
}

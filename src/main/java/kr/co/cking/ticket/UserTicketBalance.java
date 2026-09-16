package kr.co.cking.ticket;

import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_ticket_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTicketBalance {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private UserTicketBalanceId id;

    private Long balance;

    @Builder
    private UserTicketBalance(Long memberId, Long creatorId, Long balance) {
        this.id = new UserTicketBalanceId(memberId, creatorId);
        this.balance = balance;
    }

    public Long getMemberId() {
        return id.getMemberId();
    }

    public Long getCreatorId() {
        return id.getCreatorId();
    }
}

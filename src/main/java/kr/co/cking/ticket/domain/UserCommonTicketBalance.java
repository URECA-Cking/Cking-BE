package kr.co.cking.ticket.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 공용 응모권(크리에이터 무관) 잔액(이슈 #219). {@link UserTicketBalance}와 동일
 * 계약이지만 PK가 memberId 하나뿐이다 — 공용은 크리에이터가 아니다.
 */
@Entity
@Table(name = "user_common_ticket_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCommonTicketBalance {

    @Id
    @EqualsAndHashCode.Include
    private Long memberId;

    private Long balance;

    private Instant updatedAt;

    @Builder
    private UserCommonTicketBalance(Long memberId, Long balance, Instant updatedAt) {
        this.memberId = memberId;
        this.balance = balance;
        this.updatedAt = updatedAt;
    }

    public void applyDelta(long delta, Instant updatedAt) {
        this.balance += delta;
        this.updatedAt = updatedAt;
    }
}

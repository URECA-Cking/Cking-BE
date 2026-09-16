package kr.co.cking.ticket;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 응모권 Append-only 원장. {@code uk_ledger_request}(requestId)가 재전달에 대한
 * 멱등성 방어선이고, {@code ck_ledger_type_source}가 type=EARN이면
 * missionCompletionId만, type=SPEND면 eventEntryId만 채우도록 강제한다(DB 스키마
 * 찐 최종 §7).
 */
@Entity
@Table(name = "ticket_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long ledgerId;

    private Long memberId;
    private Long creatorId;
    private Long eventEntryId;
    private Long missionCompletionId;
    private Long deltaAmount;

    @Enumerated(EnumType.STRING)
    private TicketLedgerType type;

    private String reason;
    private String requestId;
    private Long balanceBefore;
    private Long balanceAfter;
    private Instant createdAt;

    @Builder
    private TicketLedger(Long memberId, Long creatorId, Long eventEntryId, Long missionCompletionId,
                          Long deltaAmount, TicketLedgerType type, String reason, String requestId,
                          Long balanceBefore, Long balanceAfter, Instant createdAt) {
        this.memberId = memberId;
        this.creatorId = creatorId;
        this.eventEntryId = eventEntryId;
        this.missionCompletionId = missionCompletionId;
        this.deltaAmount = deltaAmount;
        this.type = type;
        this.reason = reason;
        this.requestId = requestId;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }
}

package kr.co.cking.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ticket_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long ledgerId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "event_entry_id")
    private Long eventEntryId;

    @Column(name = "mission_completion_id")
    private Long missionCompletionId;

    @Column(name = "delta_amount", nullable = false)
    private Long deltaAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private TicketLedgerType type;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "request_id", length = 36, unique = true)
    private String requestId;

    @Column(name = "balance_before")
    private Long balanceBefore;

    @Column(name = "balance_after")
    private Long balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Builder
    private TicketLedger(Long ledgerId, Long memberId, Long creatorId, Long eventEntryId,
                         Long missionCompletionId, Long deltaAmount, TicketLedgerType type,
                         String reason, String requestId, Long balanceBefore, Long balanceAfter,
                         Instant createdAt) {
        this.ledgerId = ledgerId;
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

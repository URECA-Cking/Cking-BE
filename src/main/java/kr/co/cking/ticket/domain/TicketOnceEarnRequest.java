package kr.co.cking.ticket.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Redis ONCE EARN 실행 전에 평생 1회 업무키를 영속적으로 선점하는 요청이다. */
@Entity
@Table(name = "ticket_once_earn_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketOnceEarnRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long onceEarnRequestId;

    private String requestId;
    private Long memberId;
    private Long creatorId;
    private Long missionId;
    private String missionType;
    private Long amount;
    private String periodKey;
    private String payloadFingerprint;

    @Enumerated(EnumType.STRING)
    private TicketOnceEarnRequestStatus status;

    private Instant createdAt;
    private Instant acceptedAt;

    public TicketOnceEarnRequest(String requestId, Long memberId, Long creatorId, Long missionId, String missionType,
                                 Long amount, String periodKey, String payloadFingerprint, Instant createdAt) {
        this.requestId = requestId;
        this.memberId = memberId;
        this.creatorId = creatorId;
        this.missionId = missionId;
        this.missionType = missionType;
        this.amount = amount;
        this.periodKey = periodKey;
        this.payloadFingerprint = payloadFingerprint;
        this.status = TicketOnceEarnRequestStatus.PENDING;
        this.createdAt = createdAt;
    }

    public boolean hasSamePayload(String payloadFingerprint) {
        return this.payloadFingerprint.equals(payloadFingerprint);
    }

    public void accept(Instant acceptedAt) {
        this.status = TicketOnceEarnRequestStatus.ACCEPTED;
        this.acceptedAt = acceptedAt;
    }
}

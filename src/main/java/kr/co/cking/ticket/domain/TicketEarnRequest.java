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

/** DAILY와 ONCE 사이 requestId 재사용을 Redis 실행 전에 차단하는 전역 durable request다. */
@Entity
@Table(name = "ticket_earn_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketEarnRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long earnRequestId;

    private String requestId;
    private String payloadFingerprint;
    private String rewardPolicy;

    @Enumerated(EnumType.STRING)
    private TicketEarnRequestStatus status;

    private Instant createdAt;
    private Instant acceptedAt;

    public boolean hasSameRequest(String payloadFingerprint, String rewardPolicy) {
        return this.payloadFingerprint.equals(payloadFingerprint) && this.rewardPolicy.equals(rewardPolicy);
    }

    public void accept(Instant acceptedAt) {
        this.status = TicketEarnRequestStatus.ACCEPTED;
        this.acceptedAt = acceptedAt;
    }
}

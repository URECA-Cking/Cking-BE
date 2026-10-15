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

/** Redis 실행 전에 회원·보상 종류를 선점하고, 승인 상태를 보관한다. */
@Entity
@Table(name = "ticket_common_once_earn_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketCommonOnceEarnRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long onceEarnRequestId;
    private String requestId;
    private Long memberId;
    private String rewardType;
    private Long amount;
    private String payloadFingerprint;
    @Enumerated(EnumType.STRING)
    private TicketOnceEarnRequestStatus status;
    private Instant createdAt;
    private Instant executingAt;
    private Instant acceptedAt;

    /** 같은 requestId의 요청 내용이 최초 선점과 일치하는지 확인한다. */
    public boolean hasSamePayload(String fingerprint) {
        return payloadFingerprint.equals(fingerprint);
    }

    /** Redis 적립과 Stream 발행이 승인된 시각을 기록한다. */
    public void accept(Instant now) {
        status = TicketOnceEarnRequestStatus.ACCEPTED;
        acceptedAt = now;
    }
}

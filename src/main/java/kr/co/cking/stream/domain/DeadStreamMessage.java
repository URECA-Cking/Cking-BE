package kr.co.cking.stream.domain;

import jakarta.persistence.Column;
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
 * PEL에서 최대 재시도 횟수를 넘긴 EARN/SPEND Stream 메시지. 원본 Stream ID와
 * payload를 보존해 운영자가 수동 replay할 수 있게 한다(DB 스키마 찐 최종 §25,
 * 요구사항 v4 FR-14a). 동일 원본 메시지는 한 row로 유지하고(A안,
 * {@code uk_dead_stream_source}) 재시도마다 retryCount만 갱신한다.
 */
@Entity
@Table(name = "dead_stream_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadStreamMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    private String sourceStreamId;

    @Enumerated(EnumType.STRING)
    private DeadStreamType streamType;

    @Column(columnDefinition = "json")
    private String payload;

    private String requestId;
    private Long eventId;
    private Long memberId;
    private String failureReason;
    private Integer retryCount;
    private Instant lastFailedAt;

    @Enumerated(EnumType.STRING)
    private DeadStreamResolutionStatus resolutionStatus;

    private Long resolvedBy;
    private Instant resolvedAt;
    private Instant createdAt;

    @Builder
    private DeadStreamMessage(String sourceStreamId, DeadStreamType streamType, String payload,
                               String requestId, Long eventId, Long memberId, String failureReason,
                               Integer retryCount, Instant lastFailedAt,
                               DeadStreamResolutionStatus resolutionStatus, Instant createdAt) {
        this.sourceStreamId = sourceStreamId;
        this.streamType = streamType;
        this.payload = payload;
        this.requestId = requestId;
        this.eventId = eventId;
        this.memberId = memberId;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.lastFailedAt = lastFailedAt;
        this.resolutionStatus = resolutionStatus;
        this.createdAt = createdAt;
    }

    public void recordRetry(int retryCount, String failureReason, Instant failedAt) {
        this.retryCount = retryCount;
        this.failureReason = failureReason;
        this.lastFailedAt = failedAt;
    }

    public boolean isUnresolved() {
        return resolutionStatus == DeadStreamResolutionStatus.UNRESOLVED;
    }

    public void resolve(Long resolvedBy, Instant resolvedAt) {
        this.resolutionStatus = DeadStreamResolutionStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
    }
}

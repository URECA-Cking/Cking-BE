package kr.co.cking.stream.presentation.dto;

import java.time.Instant;

import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.domain.DeadStreamType;

/** 관리자에게 보여 주는 Dead Stream 메시지 요약. 원본 payload는 노출하지 않는다. */
public record DeadStreamResponse(
        Long id,
        DeadStreamType streamType,
        String sourceStreamId,
        String requestId,
        Long eventId,
        Long memberId,
        String failureReason,
        Integer retryCount,
        Instant lastFailedAt,
        DeadStreamResolutionStatus resolutionStatus,
        Long resolvedBy,
        Instant resolvedAt,
        Instant createdAt
) {

    public static DeadStreamResponse from(DeadStreamMessage message) {
        return new DeadStreamResponse(
                message.getId(), message.getStreamType(), message.getSourceStreamId(), message.getRequestId(),
                message.getEventId(), message.getMemberId(), message.getFailureReason(), message.getRetryCount(),
                message.getLastFailedAt(), message.getResolutionStatus(), message.getResolvedBy(),
                message.getResolvedAt(), message.getCreatedAt());
    }
}

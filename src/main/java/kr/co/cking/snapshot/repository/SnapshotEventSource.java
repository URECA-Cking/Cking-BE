package kr.co.cking.snapshot.repository;

import kr.co.cking.event.domain.EventStatus;

public record SnapshotEventSource(
        Long eventId,
        EventStatus status,
        int winnerCount,
        String drawMethod,
        String prizeAlgorithmVersion
) {
    public SnapshotEventSource(Long eventId, EventStatus status, int winnerCount, String drawMethod) {
        this(eventId, status, winnerCount, drawMethod, "PRIZE_WEIGHTED_V1");
    }
}

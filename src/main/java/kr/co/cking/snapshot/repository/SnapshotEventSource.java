package kr.co.cking.snapshot.repository;

import kr.co.cking.event.domain.EventStatus;

public record SnapshotEventSource(
        Long eventId,
        EventStatus status,
        int winnerCount,
        String drawMethod
) {
}

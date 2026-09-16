package kr.co.cking.snapshot.repository;

import kr.co.cking.event.EventStatus;

public record SnapshotEventSource(
        Long eventId,
        EventStatus status,
        int winnerCount,
        String drawMethod
) {
}

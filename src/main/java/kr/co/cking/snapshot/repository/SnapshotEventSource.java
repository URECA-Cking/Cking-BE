package kr.co.cking.snapshot.repository;

public record SnapshotEventSource(
        Long eventId,
        String status,
        int winnerCount,
        String drawMethod
) {
}

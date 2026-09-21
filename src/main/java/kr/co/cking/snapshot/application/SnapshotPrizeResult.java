package kr.co.cking.snapshot.application;

import kr.co.cking.snapshot.domain.DrawSnapshotPrize;

public record SnapshotPrizeResult(Long snapshotPrizeId, String prizeKey, String displayName,
                                  int priority, long weight, int quantity) {
    public static SnapshotPrizeResult from(DrawSnapshotPrize prize) {
        return new SnapshotPrizeResult(prize.getId(), prize.getPrizeKey(), prize.getDisplayName(),
                prize.getPriority(), prize.getWeight(), prize.getQuantity());
    }
}

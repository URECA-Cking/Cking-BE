package kr.co.cking.snapshot.domain;

import kr.co.cking.event.domain.PrizeConfig;

/** 공식 Snapshot에 동결되는 상품 등급 입력 값이다. */
public record PrizeValue(
        Long snapshotPrizeId,
        String prizeKey,
        String displayName,
        int priority,
        long weight,
        int quantity
) {
    public PrizeValue {
        new PrizeConfig(prizeKey, displayName, priority, weight, quantity);
        if (snapshotPrizeId != null && snapshotPrizeId <= 0) {
            throw new IllegalArgumentException("snapshotPrizeId는 양수여야 합니다.");
        }
    }

    public PrizeValue(String prizeKey, String displayName, int priority, long weight, int quantity) {
        this(null, prizeKey, displayName, priority, weight, quantity);
    }
}

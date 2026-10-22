package kr.co.cking.drawing.domain.prize;

public enum PrizeAllocationAlgorithmVersion {
    PRIZE_UNIFORM_V1,
    PRIZE_WEIGHTED_V1;

    public static PrizeAllocationAlgorithmVersion from(String value) {
        try {
            return valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("지원하지 않는 상품 배정 알고리즘입니다: " + value, exception);
        }
    }
}

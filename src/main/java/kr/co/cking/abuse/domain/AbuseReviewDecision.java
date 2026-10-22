package kr.co.cking.abuse.domain;

/** 관리자 검토에서 허용하는 종결 판정이다. DETECTED는 검토 목표가 될 수 없다. */
public enum AbuseReviewDecision {
    CONFIRMED,
    FALSE_POSITIVE;

    public AbuseDetectionStatus toStatus() {
        return switch (this) {
            case CONFIRMED -> AbuseDetectionStatus.CONFIRMED;
            case FALSE_POSITIVE -> AbuseDetectionStatus.FALSE_POSITIVE;
        };
    }
}

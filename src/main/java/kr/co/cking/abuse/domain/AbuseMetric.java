package kr.co.cking.abuse.domain;

/** Feature Store가 측정하고 Rule Engine이 임계치와 비교하는 지표 이름이다. */
public enum AbuseMetric {
    MISSION_REQUEST_COUNT("missionRequestCount"),
    DUPLICATE_MISSION_FAILURE_COUNT("duplicateMissionFailureCount"),
    ENTRY_REQUEST_COUNT("entryRequestCount"),
    INSUFFICIENT_BALANCE_FAILURE_COUNT("insufficientBalanceFailureCount"),
    INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT("insufficientBalanceConsecutiveCount"),
    DISTINCT_REQUEST_ID_COUNT("distinctRequestIdCountPerBusinessKey"),
    RAPID_EARN_SPEND_PAIR_COUNT("rapidEarnSpendPairCount"),
    FAILURE_COUNT("failureCount"),
    FAILURE_CONSECUTIVE_COUNT("failureConsecutiveCount"),
    DISTINCT_FAILURE_TYPE_COUNT("distinctFailureTypeCount");

    private final String evidenceKey;

    /** 문서화된 Evidence JSON의 lowerCamelCase 지표 이름을 보관한다. */
    AbuseMetric(String evidenceKey) {
        this.evidenceKey = evidenceKey;
    }

    /** MySQL Evidence JSON과 관리자 API에 노출할 지표 키를 반환한다. */
    public String evidenceKey() {
        return evidenceKey;
    }

    /** 문서화된 Evidence JSON 지표 키를 Domain enum으로 복원한다. */
    public static AbuseMetric fromEvidenceKey(String evidenceKey) {
        for (AbuseMetric metric : values()) {
            if (metric.evidenceKey.equals(evidenceKey)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 Evidence metric입니다: " + evidenceKey);
    }
}

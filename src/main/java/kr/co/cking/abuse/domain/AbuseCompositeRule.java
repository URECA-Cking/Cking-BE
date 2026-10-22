package kr.co.cking.abuse.domain;

/** 여러 Feature·Signal의 동시 충족을 Evidence에 남기는 복합 규칙이다. */
public enum AbuseCompositeRule {
    RULE_01("RULE-01"),
    RULE_02("RULE-02"),
    RULE_03("RULE-03"),
    RULE_04("RULE-04"),
    RULE_05("RULE-05");

    private final String evidenceValue;

    /** 문서화된 Evidence JSON의 복합 규칙 표현을 보관한다. */
    AbuseCompositeRule(String evidenceValue) {
        this.evidenceValue = evidenceValue;
    }

    /** MySQL Evidence JSON과 관리자 API에 노출할 복합 규칙 값을 반환한다. */
    public String evidenceValue() {
        return evidenceValue;
    }

    /** 문서화된 Evidence JSON 복합 규칙 값을 Domain enum으로 복원한다. */
    public static AbuseCompositeRule fromEvidenceValue(String evidenceValue) {
        for (AbuseCompositeRule rule : values()) {
            if (rule.evidenceValue.equals(evidenceValue)) {
                return rule;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 Evidence 복합 규칙입니다: " + evidenceValue);
    }
}

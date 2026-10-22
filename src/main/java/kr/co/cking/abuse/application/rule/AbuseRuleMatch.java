package kr.co.cking.abuse.application.rule;

import java.util.Objects;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;

/** Cooldown과 영속화 전에 Rule이 확정한 탐지 후보 및 근거다. */
public record AbuseRuleMatch(AbuseType abuseType, DetectionEvidence evidence) {

    public AbuseRuleMatch {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        Objects.requireNonNull(evidence, "evidence는 필수입니다.");
    }
}

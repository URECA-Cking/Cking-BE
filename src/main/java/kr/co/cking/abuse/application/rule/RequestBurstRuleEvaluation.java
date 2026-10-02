package kr.co.cking.abuse.application.rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.DetectionEvidence;

/** 요청 Burst 후보와 별도 scope·window를 가진 보조 Signal 근거를 전달한다. */
public record RequestBurstRuleEvaluation(
        List<AbuseRuleMatch> matches,
        Map<AbuseSignal, DetectionEvidence> signalEvidence
) {

    public RequestBurstRuleEvaluation {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches는 필수입니다."));
        signalEvidence = Map.copyOf(Objects.requireNonNull(signalEvidence, "signalEvidence는 필수입니다."));
    }

    public Set<AbuseSignal> signals() {
        return signalEvidence.keySet();
    }

    public static RequestBurstRuleEvaluation empty() {
        return new RequestBurstRuleEvaluation(List.of(), Map.of());
    }
}

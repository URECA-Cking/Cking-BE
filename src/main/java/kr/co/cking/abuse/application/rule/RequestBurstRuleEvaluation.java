package kr.co.cking.abuse.application.rule;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import kr.co.cking.abuse.domain.AbuseSignal;

/** 요청 Burst 탐지 후보와 단독 Detection이 아닌 보조 Signal을 함께 전달한다. */
public record RequestBurstRuleEvaluation(List<AbuseRuleMatch> matches, Set<AbuseSignal> signals) {

    public RequestBurstRuleEvaluation {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches는 필수입니다."));
        signals = Set.copyOf(Objects.requireNonNull(signals, "signals는 필수입니다."));
    }

    public static RequestBurstRuleEvaluation empty() {
        return new RequestBurstRuleEvaluation(List.of(), Set.of());
    }
}

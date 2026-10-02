package kr.co.cking.abuse.application.rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.DetectionEvidence.Scope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.event.domain.EntryResultCode;
import org.springframework.stereotype.Component;

/** 현재 성공한 응모가 같은 BalanceScope의 최근 EARN과 pair를 만든 경우만 판정한다. */
@Component
public class RapidEarnSpendRuleEvaluator {

    private final AbuseProperties properties;

    public RapidEarnSpendRuleEvaluator(AbuseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
    }

    /** Feature Store가 원자적으로 계산한 pair 생성 여부와 sliding count로 탐지 후보를 반환한다. */
    public List<AbuseRuleMatch> evaluate(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
        if (!properties.enabled()
                || observation.actionType() != AbuseActionType.EVENT_ENTRY
                || observation.resultClassification() != ResultClassification.NEW_SUCCESS
                || !EntryResultCode.SUCCESS.name().equals(observation.resultCode())) {
            return List.of();
        }
        properties.windowPolicy(); // 활성화된 설정의 완전성을 검증한다.

        long pairCreated = snapshot.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED);
        long pairCount = snapshot.valueOf(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT);
        AbuseProperties.RapidRule rule = properties.rapidEarnAndSpend();
        if (pairCreated != 1L || pairCount < 2L || pairCount < rule.threshold()) {
            return List.of();
        }

        DetectionEvidence evidence = new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new Scope(Scope.Type.USER_BALANCE_SCOPE, observation.balanceScope().creatorId(),
                        null, null, null, observation.balanceScope()),
                new DetectionEvidence.Window(rule.window().toMillis(), rule.maxDelay().toMillis()),
                Map.of(
                        AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, pairCount,
                        AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, pairCreated),
                Map.of(AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, rule.threshold().longValue()),
                Set.of(), Set.of());
        return List.of(new AbuseRuleMatch(AbuseType.RAPID_EARN_AND_SPEND, evidence));
    }
}

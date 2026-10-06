package kr.co.cking.abuse.application.rule;

import java.util.List;
import java.util.Objects;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.application.port.AbuseFeatureStore;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionResult;
import org.springframework.stereotype.Component;

/** 한 Observation의 Feature 갱신과 기본·복합 Rule 평가를 조합하는 application 경계다. */
@Component
public class AbuseRuleEvaluator {

    private final AbuseProperties properties;
    private final AbuseFeatureStore featureStore;
    private final RequestBurstRuleEvaluator requestBurstRuleEvaluator;
    private final FailureBurstRuleEvaluator failureBurstRuleEvaluator;
    private final RapidEarnSpendRuleEvaluator rapidEarnSpendRuleEvaluator;
    private final CompositeRuleEvaluator compositeRuleEvaluator;

    public AbuseRuleEvaluator(
            AbuseProperties properties,
            AbuseFeatureStore featureStore,
            RequestBurstRuleEvaluator requestBurstRuleEvaluator,
            FailureBurstRuleEvaluator failureBurstRuleEvaluator,
            RapidEarnSpendRuleEvaluator rapidEarnSpendRuleEvaluator,
            CompositeRuleEvaluator compositeRuleEvaluator
    ) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
        this.featureStore = Objects.requireNonNull(featureStore, "featureStore는 필수입니다.");
        this.requestBurstRuleEvaluator = Objects.requireNonNull(
                requestBurstRuleEvaluator, "requestBurstRuleEvaluator는 필수입니다.");
        this.failureBurstRuleEvaluator = Objects.requireNonNull(
                failureBurstRuleEvaluator, "failureBurstRuleEvaluator는 필수입니다.");
        this.rapidEarnSpendRuleEvaluator = Objects.requireNonNull(
                rapidEarnSpendRuleEvaluator, "rapidEarnSpendRuleEvaluator는 필수입니다.");
        this.compositeRuleEvaluator = Objects.requireNonNull(
                compositeRuleEvaluator, "compositeRuleEvaluator는 필수입니다.");
    }

    /** 장애를 빈 정상 결과로 바꾸지 않는다. 원 업무의 Fail Open은 Observation 호출 경계가 맡는다. */
    public List<DetectionResult> evaluate(AbuseObservationEvent observation) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        if (!properties.enabled()) {
            return List.of();
        }

        AbuseFeatureSnapshot snapshot = featureStore.record(observation, properties.windowPolicy());
        RequestBurstRuleEvaluation request = requestBurstRuleEvaluator.evaluate(observation, snapshot);
        List<AbuseRuleMatch> failure = failureBurstRuleEvaluator.evaluate(observation, snapshot);
        List<AbuseRuleMatch> rapid = rapidEarnSpendRuleEvaluator.evaluate(observation, snapshot);
        return compositeRuleEvaluator.enrich(observation, snapshot, request, failure, rapid).stream()
                .map(match -> new DetectionResult(observation.userId(), match.abuseType(),
                        cooldownScopeHash(observation, match.abuseType()), observation.observedAt(), match.evidence()))
                .toList();
    }

    /** Detection 유형별 canonical scope를 해시해 Redis Cooldown key에 전달한다. */
    private String cooldownScopeHash(AbuseObservationEvent observation, AbuseType type) {
        String canonicalScope = switch (type) {
            case MISSION_REQUEST_BURST, FAILURE_BURST -> "USER:" + observation.userId();
            case DUPLICATE_MISSION_BURST -> "BUSINESS_KEY:" + observation.businessKey();
            case ENTRY_REQUEST_BURST -> "USER_EVENT:" + observation.userId() + ":" + observation.eventId();
            case INSUFFICIENT_BALANCE_BURST, RAPID_EARN_AND_SPEND ->
                    "USER_BALANCE_SCOPE:" + observation.userId() + ":" + observation.balanceScope().canonicalValue();
        };
        return AbuseScopeHash.fromCanonicalValue(canonicalScope);
    }
}

package kr.co.cking.abuse.application.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

/** 업무 실패의 sliding count와 연속 count를 잔액 범위 또는 사용자 범위에서 판정한다. */
@Component
public class FailureBurstRuleEvaluator {

    private final AbuseProperties properties;

    public FailureBurstRuleEvaluator(AbuseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
    }

    /** Feature Store가 확정한 현재 Observation의 값으로 탐지 후보만 반환한다. */
    public List<AbuseRuleMatch> evaluate(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
        if (!properties.enabled() || observation.resultClassification() != ResultClassification.BUSINESS_FAILURE) {
            return List.of();
        }
        properties.windowPolicy(); // 활성화된 설정의 완전성을 검증한다.

        List<AbuseRuleMatch> matches = new ArrayList<>();
        if (observation.actionType() == AbuseActionType.EVENT_ENTRY
                && EntryResultCode.INSUFFICIENT_BALANCE.name().equals(observation.resultCode())) {
            matchInsufficientBalance(observation, snapshot).ifPresent(matches::add);
        }
        matchFailure(snapshot).ifPresent(matches::add);
        return List.copyOf(matches);
    }

    private Optional<AbuseRuleMatch> matchInsufficientBalance(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        AbuseProperties.ConsecutiveRule rule = properties.insufficientBalanceBurst();
        long count = snapshot.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT);
        long consecutive = snapshot.valueOf(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT);
        if (count < rule.threshold() && consecutive < rule.consecutiveThreshold()) {
            return Optional.empty();
        }
        DetectionEvidence evidence = new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new Scope(Scope.Type.USER_BALANCE_SCOPE, observation.balanceScope().creatorId(),
                        null, null, null, observation.balanceScope()),
                new DetectionEvidence.Window(rule.window().toMillis(), null),
                Map.of(
                        AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, count,
                        AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, consecutive),
                Map.of(
                        AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, rule.threshold().longValue(),
                        AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT,
                        rule.consecutiveThreshold().longValue()),
                Set.of(), Set.of());
        return Optional.of(new AbuseRuleMatch(AbuseType.INSUFFICIENT_BALANCE_BURST, evidence));
    }

    private Optional<AbuseRuleMatch> matchFailure(AbuseFeatureSnapshot snapshot) {
        AbuseProperties.FailureRule rule = properties.failureBurst();
        long count = snapshot.valueOf(AbuseMetric.FAILURE_COUNT);
        long consecutive = snapshot.valueOf(AbuseMetric.FAILURE_CONSECUTIVE_COUNT);
        if (count < rule.threshold() && consecutive < rule.consecutiveThreshold()) {
            return Optional.empty();
        }
        DetectionEvidence evidence = new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new Scope(Scope.Type.USER, null, null, null, null, null),
                new DetectionEvidence.Window(rule.window().toMillis(), null),
                Map.of(
                        AbuseMetric.FAILURE_COUNT, count,
                        AbuseMetric.FAILURE_CONSECUTIVE_COUNT, consecutive,
                        AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT,
                        snapshot.valueOf(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT)),
                Map.of(
                        AbuseMetric.FAILURE_COUNT, rule.threshold().longValue(),
                        AbuseMetric.FAILURE_CONSECUTIVE_COUNT, rule.consecutiveThreshold().longValue(),
                        AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, rule.distinctTypeThreshold().longValue()),
                Set.of(), Set.of());
        return Optional.of(new AbuseRuleMatch(AbuseType.FAILURE_BURST, evidence));
    }
}

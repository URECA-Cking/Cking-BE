package kr.co.cking.abuse.application.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.application.port.AbuseFeatureStore;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionResult;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.Test;

class AbuseRuleEvaluatorTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-10-06T08:00:00Z");
    private final AbuseFeatureStore featureStore = mock(AbuseFeatureStore.class);
    private final AbuseProperties properties = properties(true);
    private final AbuseRuleEvaluator evaluator = evaluator(properties);

    @Test
    void 비활성_설정은_Feature_Store를_호출하지_않는다() {
        AbuseRuleEvaluator disabled = evaluator(properties(false));

        assertThat(disabled.evaluate(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS))).isEmpty();
        verifyNoInteractions(featureStore);
    }

    @Test
    void 하나의_Snapshot으로_기본_Rule과_Rotation과_Composite를_평가한다() {
        AbuseObservationEvent observation = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE);
        stub(observation, Map.of(
                AbuseMetric.MISSION_REQUEST_COUNT, 3L,
                AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L,
                AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L,
                AbuseMetric.FAILURE_COUNT, 2L,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 1L,
                AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 2L));

        List<DetectionResult> results = evaluator.evaluate(observation);

        assertThat(results).extracting(DetectionResult::abuseType).containsExactly(
                AbuseType.MISSION_REQUEST_BURST, AbuseType.DUPLICATE_MISSION_BURST, AbuseType.FAILURE_BURST);
        assertThat(results).allSatisfy(result -> assertThat(result.detectedAt()).isEqualTo(OBSERVED_AT));
        assertThat(find(results, AbuseType.MISSION_REQUEST_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_02);
        assertThat(find(results, AbuseType.DUPLICATE_MISSION_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_01);
        assertThat(find(results, AbuseType.FAILURE_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_05);
        assertThat(find(results, AbuseType.MISSION_REQUEST_BURST).cooldownScopeHash())
                .isEqualTo(hash("USER:17"));
        assertThat(find(results, AbuseType.FAILURE_BURST).cooldownScopeHash())
                .isEqualTo(hash("USER:17"));
        assertThat(find(results, AbuseType.DUPLICATE_MISSION_BURST).cooldownScopeHash())
                .isEqualTo(hash("BUSINESS_KEY:" + observation.businessKey()));
        verify(featureStore).record(eq(observation), eq(properties.windowPolicy()));
    }

    @Test
    void 응모의_세_기본_Rule을_한번씩_평가하고_각각_맞는_Cooldown_범위를_사용한다() {
        AbuseObservationEvent observation = entry("INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE,
                BalanceScope.creator(5L), 21L);
        stub(observation, Map.of(
                AbuseMetric.ENTRY_REQUEST_COUNT, 4L,
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 2L,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 1L,
                AbuseMetric.FAILURE_COUNT, 2L,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 1L,
                AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 1L));

        List<DetectionResult> results = evaluator.evaluate(observation);

        assertThat(results).extracting(DetectionResult::abuseType).containsExactly(
                AbuseType.ENTRY_REQUEST_BURST, AbuseType.INSUFFICIENT_BALANCE_BURST, AbuseType.FAILURE_BURST);
        assertThat(find(results, AbuseType.ENTRY_REQUEST_BURST).cooldownScopeHash())
                .isEqualTo(hash("USER_EVENT:17:21"));
        assertThat(find(results, AbuseType.INSUFFICIENT_BALANCE_BURST).cooldownScopeHash())
                .isEqualTo(hash("USER_BALANCE_SCOPE:17:CREATOR:5"));
        assertThat(find(results, AbuseType.FAILURE_BURST).cooldownScopeHash())
                .isEqualTo(hash("USER:17"));
        assertThat(results).allSatisfy(result -> assertThat(result.evidence().matchedRules())
                .contains(AbuseCompositeRule.RULE_03));
        verify(featureStore).record(eq(observation), eq(properties.windowPolicy()));
    }

    @Test
    void 빠른_pair와_이전_Mission_Burst는_새_Mission_Detection_없이_RULE04로_합쳐진다() {
        AbuseObservationEvent observation = entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common(), 21L);
        stub(observation, Map.of(
                AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L,
                AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 2L,
                AbuseMetric.MISSION_REQUEST_COUNT, 3L));

        List<DetectionResult> results = evaluator.evaluate(observation);

        assertThat(results).extracting(DetectionResult::abuseType).containsExactly(AbuseType.RAPID_EARN_AND_SPEND);
        assertThat(results.getFirst().evidence().matchedRules()).containsExactly(AbuseCompositeRule.RULE_04);
        assertThat(results.getFirst().evidence().supportingEvidence()).containsKey(AbuseCompositeRule.RULE_04);
        assertThat(results.getFirst().cooldownScopeHash()).isEqualTo(hash("USER_BALANCE_SCOPE:17:COMMON"));
    }

    @Test
    void Rotation만_충족하면_DetectionResult를_생성하지_않는다() {
        AbuseObservationEvent observation = mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS);
        stub(observation, Map.of(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L));

        assertThat(evaluator.evaluate(observation)).isEmpty();
        verify(featureStore).record(eq(observation), eq(properties.windowPolicy()));
    }

    @Test
    void Replay와_System_Failure는_현재_탐지_후보를_만들지_않는다() {
        for (ResultClassification classification : List.of(
                ResultClassification.REPLAY, ResultClassification.SYSTEM_FAILURE)) {
            AbuseObservationEvent observation = mission("IGNORED", classification);
            stub(observation, Map.of(
                    AbuseMetric.MISSION_REQUEST_COUNT, 10L,
                    AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 10L,
                    AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 10L,
                    AbuseMetric.FAILURE_COUNT, 10L));

            assertThat(evaluator.evaluate(observation)).isEmpty();
        }
    }

    @Test
    void Event와_Balance_Scope의_Cooldown_Hash는_서로_독립적이다() {
        AbuseObservationEvent creatorEvent = entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.creator(5L), 21L);
        AbuseObservationEvent otherEvent = entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.creator(5L), 22L);
        AbuseObservationEvent commonEvent = entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.common(), 21L);
        Map<AbuseMetric, Long> values = Map.of(
                AbuseMetric.ENTRY_REQUEST_COUNT, 4L,
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 2L,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 1L);
        stub(creatorEvent, values);
        stub(otherEvent, values);
        stub(commonEvent, values);

        List<DetectionResult> first = evaluator.evaluate(creatorEvent);
        List<DetectionResult> second = evaluator.evaluate(otherEvent);
        List<DetectionResult> third = evaluator.evaluate(commonEvent);

        assertThat(find(first, AbuseType.ENTRY_REQUEST_BURST).cooldownScopeHash())
                .isNotEqualTo(find(second, AbuseType.ENTRY_REQUEST_BURST).cooldownScopeHash())
                .isEqualTo(find(third, AbuseType.ENTRY_REQUEST_BURST).cooldownScopeHash());
        assertThat(find(first, AbuseType.INSUFFICIENT_BALANCE_BURST).cooldownScopeHash())
                .isEqualTo(find(second, AbuseType.INSUFFICIENT_BALANCE_BURST).cooldownScopeHash())
                .isNotEqualTo(find(third, AbuseType.INSUFFICIENT_BALANCE_BURST).cooldownScopeHash());
    }

    @Test
    void 같은_사용자의_다른_Mission_Business_Key는_별도_Cooldown_범위다() {
        AbuseObservationEvent firstObservation = mission(
                "DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE);
        AbuseObservationEvent secondObservation = new AbuseObservationEvent(
                UUID.randomUUID(), firstObservation.userId(), AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(), "DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                5L, null, 104L, "2026-10-06", BalanceScope.creator(5L),
                "MISSION:CREATOR:DAILY:17:5:104:2026-10-06", OBSERVED_AT.minusMillis(10), OBSERVED_AT);
        Map<AbuseMetric, Long> values = Map.of(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L);
        stub(firstObservation, values);
        stub(secondObservation, values);

        DetectionResult first = find(evaluator.evaluate(firstObservation), AbuseType.DUPLICATE_MISSION_BURST);
        DetectionResult second = find(evaluator.evaluate(secondObservation), AbuseType.DUPLICATE_MISSION_BURST);

        assertThat(first.cooldownScopeHash()).isNotEqualTo(second.cooldownScopeHash());
    }

    @Test
    void Feature_Store_장애를_빈_정상_결과로_숨기지_않는다() {
        AbuseObservationEvent observation = mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS);
        IllegalStateException failure = new IllegalStateException("Redis unavailable");
        when(featureStore.record(eq(observation), any())).thenThrow(failure);

        assertThatThrownBy(() -> evaluator.evaluate(observation)).isSameAs(failure);
    }

    @Test
    void Rule_평가_예외도_빈_정상_결과로_숨기지_않는다() {
        AbuseObservationEvent observation = mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS);
        stub(observation, Map.of());
        RequestBurstRuleEvaluator brokenRule = mock(RequestBurstRuleEvaluator.class);
        IllegalArgumentException failure = new IllegalArgumentException("rule failure");
        when(brokenRule.evaluate(eq(observation), any())).thenThrow(failure);
        AbuseRuleEvaluator brokenEvaluator = new AbuseRuleEvaluator(properties, featureStore, brokenRule,
                new FailureBurstRuleEvaluator(properties), new RapidEarnSpendRuleEvaluator(properties),
                new CompositeRuleEvaluator(properties));

        assertThatThrownBy(() -> brokenEvaluator.evaluate(observation)).isSameAs(failure);
    }

    private AbuseRuleEvaluator evaluator(AbuseProperties activeProperties) {
        return new AbuseRuleEvaluator(activeProperties, featureStore,
                new RequestBurstRuleEvaluator(activeProperties),
                new FailureBurstRuleEvaluator(activeProperties),
                new RapidEarnSpendRuleEvaluator(activeProperties),
                new CompositeRuleEvaluator(activeProperties));
    }

    private void stub(AbuseObservationEvent observation, Map<AbuseMetric, Long> values) {
        when(featureStore.record(eq(observation), any())).thenReturn(new AbuseFeatureSnapshot(values));
    }

    private DetectionResult find(List<DetectionResult> results, AbuseType type) {
        return results.stream().filter(result -> result.abuseType() == type).findFirst().orElseThrow();
    }

    private String hash(String canonicalScope) {
        return AbuseScopeHash.fromCanonicalValue(canonicalScope);
    }

    private AbuseObservationEvent mission(String resultCode, ResultClassification classification) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(), resultCode, classification, 5L, null, 103L, "2026-10-06",
                BalanceScope.creator(5L), "MISSION:CREATOR:DAILY:17:5:103:2026-10-06",
                OBSERVED_AT.minusMillis(10), OBSERVED_AT);
    }

    private AbuseObservationEvent entry(
            String resultCode, ResultClassification classification, BalanceScope balanceScope, Long eventId
    ) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.EVENT_ENTRY,
                UUID.randomUUID(), resultCode, classification, 5L, eventId, null, null,
                balanceScope, null, OBSERVED_AT.minusMillis(10), OBSERVED_AT);
    }

    private AbuseProperties properties(boolean enabled) {
        return new AbuseProperties(enabled,
                new AbuseProperties.CountRule(Duration.ofSeconds(10), 3),
                new AbuseProperties.CountRule(Duration.ofSeconds(20), 2),
                new AbuseProperties.CountRule(Duration.ofSeconds(30), 4),
                new AbuseProperties.ConsecutiveRule(Duration.ofSeconds(40), 2, 2),
                new AbuseProperties.RotationRule(Duration.ofSeconds(50), 3),
                new AbuseProperties.RapidRule(Duration.ofSeconds(2), Duration.ofSeconds(60), 2),
                new AbuseProperties.FailureRule(Duration.ofSeconds(70), 2, 2, 2));
    }
}

package kr.co.cking.abuse.application.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.Test;

class CompositeRuleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private final AbuseProperties properties = properties(true);
    private final CompositeRuleEvaluator evaluator = new CompositeRuleEvaluator(properties);

    @Test
    void 중복_미션과_요청_Burst가_회전_Signal과_겹치면_각_후보에_별도_근거를_보존한다() {
        AbuseObservationEvent observation = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE);
        List<AbuseRuleMatch> matches = evaluate(observation, snapshot(Map.of(
                AbuseMetric.MISSION_REQUEST_COUNT, 3L,
                AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L,
                AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L)));

        assertThat(matches).extracting(AbuseRuleMatch::abuseType).containsExactly(
                AbuseType.MISSION_REQUEST_BURST, AbuseType.DUPLICATE_MISSION_BURST);
        assertThat(find(matches, AbuseType.MISSION_REQUEST_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_02);
        assertThat(find(matches, AbuseType.DUPLICATE_MISSION_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_01);

        DetectionEvidence primary = find(matches, AbuseType.MISSION_REQUEST_BURST).evidence();
        DetectionEvidence.SupportingEvidence rotation = primary.supportingEvidence()
                .get(AbuseCompositeRule.RULE_02).getFirst();
        assertThat(primary.scope().type()).isEqualTo(DetectionEvidence.Scope.Type.USER);
        assertThat(primary.window().windowMs()).isEqualTo(10_000L);
        assertThat(primary.features()).doesNotContainKey(AbuseMetric.DISTINCT_REQUEST_ID_COUNT);
        assertThat(primary.signals()).containsExactly(AbuseSignal.REQUEST_ID_ROTATION);
        assertThat(rotation.signal()).isEqualTo(AbuseSignal.REQUEST_ID_ROTATION);
        assertThat(rotation.scope().type()).isEqualTo(DetectionEvidence.Scope.Type.BUSINESS_KEY);
        assertThat(rotation.scope().missionId()).isEqualTo(observation.missionId());
        assertThat(rotation.window().windowMs()).isEqualTo(50_000L);
        assertThat(rotation.features()).containsEntry(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L);
        assertThat(rotation.thresholds()).containsEntry(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L);
    }

    @Test
    void 회전_Signal만_충족해도_단독_Detection은_생성하지_않는다() {
        AbuseObservationEvent observation = mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS);
        assertThat(evaluate(observation, snapshot(Map.of(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L)))).isEmpty();
    }

    @Test
    void 응모_Burst와_잔액_부족_및_실패_Burst가_겹치면_RULE03과_RULE05를_함께_기록한다() {
        AbuseObservationEvent observation = entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.creator(5L));
        List<AbuseRuleMatch> matches = evaluate(observation, snapshot(Map.of(
                AbuseMetric.ENTRY_REQUEST_COUNT, 4L,
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 2L,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 1L,
                AbuseMetric.FAILURE_COUNT, 2L,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 1L,
                AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 2L)));

        assertThat(matches).hasSize(3);
        DetectionEvidence entry = find(matches, AbuseType.ENTRY_REQUEST_BURST).evidence();
        assertThat(entry.matchedRules()).containsExactly(AbuseCompositeRule.RULE_03);
        assertThat(entry.supportingEvidence().get(AbuseCompositeRule.RULE_03))
                .extracting(DetectionEvidence.SupportingEvidence::abuseType)
                .containsExactlyInAnyOrder(AbuseType.INSUFFICIENT_BALANCE_BURST, AbuseType.FAILURE_BURST);
        assertThat(entry.scope().type()).isEqualTo(DetectionEvidence.Scope.Type.USER_EVENT);
        assertThat(entry.window().windowMs()).isEqualTo(30_000L);
        assertThat(entry.supportingEvidence().get(AbuseCompositeRule.RULE_03))
                .anySatisfy(support -> {
                    assertThat(support.scope().type()).isEqualTo(DetectionEvidence.Scope.Type.USER_BALANCE_SCOPE);
                    assertThat(support.window().windowMs()).isEqualTo(40_000L);
                });
        assertThat(find(matches, AbuseType.INSUFFICIENT_BALANCE_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_03);
        DetectionEvidence failure = find(matches, AbuseType.FAILURE_BURST).evidence();
        assertThat(failure.matchedRules()).containsExactlyInAnyOrder(
                AbuseCompositeRule.RULE_03, AbuseCompositeRule.RULE_05);
        assertThat(failure.features()).containsEntry(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 2L);
        assertThat(failure.supportingEvidence()).containsOnlyKeys(AbuseCompositeRule.RULE_03);
    }

    @Test
    void Mission_업무_실패_Burst에도_서로_다른_실패_유형이_충족되면_RULE05를_기록한다() {
        AbuseObservationEvent observation = mission("MISSION_INACTIVE", ResultClassification.BUSINESS_FAILURE);
        List<AbuseRuleMatch> matches = evaluate(observation, snapshot(Map.of(
                AbuseMetric.FAILURE_COUNT, 2L,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 1L,
                AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 2L)));

        assertThat(matches).extracting(AbuseRuleMatch::abuseType).containsExactly(AbuseType.FAILURE_BURST);
        assertThat(matches.getFirst().evidence().matchedRules()).containsExactly(AbuseCompositeRule.RULE_05);
        assertThat(matches.getFirst().evidence().supportingEvidence()).isEmpty();
    }

    @Test
    void 빠른_pair와_현재_응모_Burst_및_이전_Mission_Burst가_모두_있으면_각_근거를_분리한다() {
        AbuseObservationEvent observation = entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common());
        List<AbuseRuleMatch> matches = evaluate(observation, snapshot(Map.of(
                AbuseMetric.ENTRY_REQUEST_COUNT, 4L,
                AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L,
                AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 2L,
                AbuseMetric.MISSION_REQUEST_COUNT, 3L)));

        assertThat(matches).extracting(AbuseRuleMatch::abuseType).containsExactly(
                AbuseType.ENTRY_REQUEST_BURST, AbuseType.RAPID_EARN_AND_SPEND);
        DetectionEvidence rapid = find(matches, AbuseType.RAPID_EARN_AND_SPEND).evidence();
        assertThat(rapid.matchedRules()).containsExactly(AbuseCompositeRule.RULE_04);
        assertThat(rapid.supportingEvidence().get(AbuseCompositeRule.RULE_04))
                .extracting(DetectionEvidence.SupportingEvidence::abuseType)
                .containsExactlyInAnyOrder(AbuseType.ENTRY_REQUEST_BURST, AbuseType.MISSION_REQUEST_BURST);
        assertThat(rapid.window()).isEqualTo(new DetectionEvidence.Window(60_000L, 2_000L));
        assertThat(rapid.supportingEvidence().get(AbuseCompositeRule.RULE_04))
                .anySatisfy(support -> {
                    assertThat(support.abuseType()).isEqualTo(AbuseType.MISSION_REQUEST_BURST);
                    assertThat(support.scope().type()).isEqualTo(DetectionEvidence.Scope.Type.USER);
                    assertThat(support.window().windowMs()).isEqualTo(10_000L);
                    assertThat(support.features()).containsEntry(AbuseMetric.MISSION_REQUEST_COUNT, 3L);
                });
        assertThat(find(matches, AbuseType.ENTRY_REQUEST_BURST).evidence().matchedRules())
                .containsExactly(AbuseCompositeRule.RULE_04);
    }

    @Test
    void 이전_Mission_Burst_조회값만_있어도_빠른_pair에_RULE04를_추가하되_별도_Mission_row는_만들지_않는다() {
        AbuseObservationEvent observation = entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common());
        List<AbuseRuleMatch> matches = evaluate(observation, snapshot(Map.of(
                AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L,
                AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 2L,
                AbuseMetric.MISSION_REQUEST_COUNT, 3L)));

        assertThat(matches).extracting(AbuseRuleMatch::abuseType).containsExactly(AbuseType.RAPID_EARN_AND_SPEND);
        assertThat(matches.getFirst().evidence().matchedRules()).containsExactly(AbuseCompositeRule.RULE_04);
        assertThat(matches.getFirst().evidence().supportingEvidence().get(AbuseCompositeRule.RULE_04))
                .extracting(DetectionEvidence.SupportingEvidence::abuseType)
                .containsExactly(AbuseType.MISSION_REQUEST_BURST);
    }

    @Test
    void 이전_Mission_Feature가_없거나_임계치_미만이면_RULE04를_추측하지_않는다() {
        AbuseObservationEvent observation = entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common());
        for (Map<AbuseMetric, Long> values : List.of(
                Map.of(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L,
                        AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 2L),
                Map.of(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L,
                        AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 2L,
                        AbuseMetric.MISSION_REQUEST_COUNT, 2L))) {
            List<AbuseRuleMatch> matches = evaluate(observation, snapshot(values));
            assertThat(matches).hasSize(1);
            assertThat(matches.getFirst().evidence().matchedRules()).isEmpty();
        }
    }

    @Test
    void 다른_Business_Key의_회전_근거와_다른_BalanceScope의_후보는_섞지_않는다() {
        AbuseObservationEvent mission = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE);
        AbuseFeatureSnapshot snapshot = snapshot(Map.of(
                AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L,
                AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L));
        RequestBurstRuleEvaluation original = new RequestBurstRuleEvaluator(properties).evaluate(mission, snapshot);
        DetectionEvidence rotation = original.signalEvidence().get(AbuseSignal.REQUEST_ID_ROTATION);
        DetectionEvidence wrongRotation = new DetectionEvidence(rotation.policyVersion(),
                new DetectionEvidence.Scope(DetectionEvidence.Scope.Type.BUSINESS_KEY,
                        5L, null, 999L, "2026-10-02", BalanceScope.creator(5L)),
                rotation.window(), rotation.features(), rotation.thresholds(), rotation.signals(),
                rotation.matchedRules());

        assertThatThrownBy(() -> evaluator.enrich(mission, snapshot,
                new RequestBurstRuleEvaluation(original.matches(),
                        Map.of(AbuseSignal.REQUEST_ID_ROTATION, wrongRotation)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        AbuseObservationEvent entry = entry("SUCCESS", ResultClassification.NEW_SUCCESS, BalanceScope.common());
        AbuseRuleMatch creatorRapid = new RapidEarnSpendRuleEvaluator(properties).evaluate(
                entry("SUCCESS", ResultClassification.NEW_SUCCESS, BalanceScope.creator(5L)),
                snapshot(Map.of(AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L,
                        AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 2L))).getFirst();
        assertThatThrownBy(() -> evaluator.enrich(entry, snapshot,
                RequestBurstRuleEvaluation.empty(), List.of(), List.of(creatorRapid)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 비활성_정책은_후보가_있어도_Composite_결과를_내지_않는다() {
        CompositeRuleEvaluator disabled = new CompositeRuleEvaluator(properties(false));
        AbuseObservationEvent observation = entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common());
        assertThat(disabled.enrich(observation, snapshot(Map.of()),
                RequestBurstRuleEvaluation.empty(), List.of(), List.of())).isEmpty();
    }

    private List<AbuseRuleMatch> evaluate(AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot) {
        RequestBurstRuleEvaluation request = new RequestBurstRuleEvaluator(properties).evaluate(observation, snapshot);
        List<AbuseRuleMatch> failure = new FailureBurstRuleEvaluator(properties).evaluate(observation, snapshot);
        List<AbuseRuleMatch> rapid = new RapidEarnSpendRuleEvaluator(properties).evaluate(observation, snapshot);
        return evaluator.enrich(observation, snapshot, request, failure, rapid);
    }

    private AbuseRuleMatch find(List<AbuseRuleMatch> matches, AbuseType type) {
        return matches.stream().filter(match -> match.abuseType() == type).findFirst().orElseThrow();
    }

    private AbuseFeatureSnapshot snapshot(Map<AbuseMetric, Long> values) {
        return new AbuseFeatureSnapshot(values);
    }

    private AbuseObservationEvent mission(String resultCode, ResultClassification classification) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(), resultCode, classification, 5L, null, 103L, "2026-10-02",
                BalanceScope.creator(5L), "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", NOW, NOW);
    }

    private AbuseObservationEvent entry(String resultCode, ResultClassification classification, BalanceScope scope) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.EVENT_ENTRY,
                UUID.randomUUID(), resultCode, classification, 5L, 21L, null, null,
                scope, null, NOW, NOW);
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

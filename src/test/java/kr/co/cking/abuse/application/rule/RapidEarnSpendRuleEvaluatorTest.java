package kr.co.cking.abuse.application.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.Test;

class RapidEarnSpendRuleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private final RapidEarnSpendRuleEvaluator evaluator = new RapidEarnSpendRuleEvaluator(properties(true, 3));

    @Test
    void 새_pair의_누적_count가_임계치에_도달하면_BalanceScope와_시간_근거를_보존한다() {
        for (BalanceScope scope : List.of(BalanceScope.common(), BalanceScope.creator(5L))) {
            assertThat(evaluator.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS, scope),
                    snapshot(1L, 2L))).isEmpty();

            List<AbuseRuleMatch> matches = evaluator.evaluate(
                    entry("SUCCESS", ResultClassification.NEW_SUCCESS, scope), snapshot(1L, 3L));

            assertThat(matches).hasSize(1);
            AbuseRuleMatch match = matches.getFirst();
            assertThat(match.abuseType()).isEqualTo(AbuseType.RAPID_EARN_AND_SPEND);
            assertThat(match.evidence().scope().type())
                    .isEqualTo(DetectionEvidence.Scope.Type.USER_BALANCE_SCOPE);
            assertThat(match.evidence().scope().balanceScope()).isEqualTo(scope);
            assertThat(match.evidence().scope().creatorId()).isEqualTo(scope.creatorId());
            assertThat(match.evidence().window()).isEqualTo(new DetectionEvidence.Window(10_000L, 2_000L));
            assertThat(match.evidence().features()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 3L,
                    AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, 1L));
            assertThat(match.evidence().thresholds()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 3L));
            assertThat(match.evidence().signals()).isEmpty();
            assertThat(match.evidence().matchedRules()).isEmpty();
        }
    }

    @Test
    void 단일_pair는_설정_threshold가_1이어도_탐지하지_않는다() {
        RapidEarnSpendRuleEvaluator thresholdOne = new RapidEarnSpendRuleEvaluator(properties(true, 1));

        assertThat(thresholdOne.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common()), snapshot(1L, 1L))).isEmpty();
        assertThat(thresholdOne.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common()), snapshot(1L, 2L))).hasSize(1);
    }

    @Test
    void 이전_pair가_많아도_이번_응모가_같은_scope와_시간의_pair를_만들지_않으면_탐지하지_않는다() {
        assertThat(evaluator.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.creator(5L)), snapshot(0L, 100L))).isEmpty();
        assertThat(evaluator.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common()), snapshot(0L, 100L))).isEmpty();
        assertThat(evaluator.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common()), new AbuseFeatureSnapshot(Map.of(
                        AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, 100L)))).isEmpty();
    }

    @Test
    void 미션_EARN과_실패_Replay_시스템_오류는_집계가_높아도_탐지하지_않는다() {
        AbuseFeatureSnapshot highCount = snapshot(1L, 100L);
        assertThat(evaluator.evaluate(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS),
                highCount)).isEmpty();
        for (AbuseObservationEvent observation : List.of(
                entry("DUPLICATE_REPLAY", ResultClassification.REPLAY, BalanceScope.common()),
                entry("EVENT_CLOSED", ResultClassification.BUSINESS_FAILURE, BalanceScope.common()),
                entry("SYSTEM_ERROR", ResultClassification.SYSTEM_FAILURE, BalanceScope.common()),
                entry("SUCCESS", ResultClassification.REPLAY, BalanceScope.common()))) {
            assertThat(evaluator.evaluate(observation, highCount)).isEmpty();
        }
    }

    @Test
    void 비활성_정책은_입력_Feature가_높아도_탐지하지_않는다() {
        RapidEarnSpendRuleEvaluator disabled = new RapidEarnSpendRuleEvaluator(properties(false, 3));

        assertThat(disabled.evaluate(entry("SUCCESS", ResultClassification.NEW_SUCCESS,
                BalanceScope.common()), snapshot(1L, 100L))).isEmpty();
    }

    private AbuseFeatureSnapshot snapshot(long pairCreated, long pairCount) {
        return new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.RAPID_EARN_SPEND_PAIR_CREATED, pairCreated,
                AbuseMetric.RAPID_EARN_SPEND_PAIR_COUNT, pairCount));
    }

    private AbuseObservationEvent entry(
            String resultCode, ResultClassification classification, BalanceScope scope
    ) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.EVENT_ENTRY,
                UUID.randomUUID(), resultCode, classification, 5L, 21L, null, null,
                scope, null, NOW, NOW);
    }

    private AbuseObservationEvent mission(String resultCode, ResultClassification classification) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(), resultCode, classification, 5L, null, 103L, "2026-10-02",
                BalanceScope.creator(5L), "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", NOW, NOW);
    }

    private AbuseProperties properties(boolean enabled, int rapidThreshold) {
        Duration window = Duration.ofSeconds(10);
        return new AbuseProperties(enabled,
                new AbuseProperties.CountRule(window, 3),
                new AbuseProperties.CountRule(window, 2),
                new AbuseProperties.CountRule(window, 4),
                new AbuseProperties.ConsecutiveRule(Duration.ofSeconds(20), 3, 2),
                new AbuseProperties.RotationRule(Duration.ofSeconds(30), 3),
                new AbuseProperties.RapidRule(Duration.ofSeconds(2), window, rapidThreshold),
                new AbuseProperties.FailureRule(Duration.ofSeconds(45), 3, 2, 2));
    }
}

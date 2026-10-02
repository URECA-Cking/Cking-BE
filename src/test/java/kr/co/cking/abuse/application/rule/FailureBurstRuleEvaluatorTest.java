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

class FailureBurstRuleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private final FailureBurstRuleEvaluator evaluator = new FailureBurstRuleEvaluator(properties(true));

    @Test
    void 잔액_부족_count는_임계치부터_해당_BalanceScope에서만_탐지한다() {
        AbuseObservationEvent creator = entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.creator(5L));
        assertThat(evaluator.evaluate(creator, insufficient(2L, 1L))).isEmpty();

        for (BalanceScope balanceScope : List.of(
                BalanceScope.common(), BalanceScope.creator(5L), BalanceScope.creator(6L))) {
            Long eventCreatorId = balanceScope.creatorId() == null ? 5L : balanceScope.creatorId();
            AbuseRuleMatch match = evaluator.evaluate(entry(eventCreatorId, "INSUFFICIENT_BALANCE",
                    ResultClassification.BUSINESS_FAILURE, balanceScope), insufficient(3L, 1L)).getFirst();
            assertThat(match.abuseType()).isEqualTo(AbuseType.INSUFFICIENT_BALANCE_BURST);
            assertThat(match.evidence().scope().type())
                    .isEqualTo(DetectionEvidence.Scope.Type.USER_BALANCE_SCOPE);
            assertThat(match.evidence().scope().balanceScope()).isEqualTo(balanceScope);
            assertThat(match.evidence().scope().creatorId()).isEqualTo(balanceScope.creatorId());
            assertThat(match.evidence().window().windowMs()).isEqualTo(20_000L);
            assertThat(match.evidence().features()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 3L,
                    AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 1L));
            assertThat(match.evidence().thresholds()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 3L,
                    AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 2L));
        }
    }

    @Test
    void 잔액_부족_연속_count도_단독으로_매칭하며_두_조건이_충족되어도_후보는_하나다() {
        AbuseObservationEvent observation = entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.common());
        AbuseRuleMatch consecutiveOnly = evaluator.evaluate(observation, insufficient(1L, 2L)).getFirst();
        assertThat(consecutiveOnly.abuseType()).isEqualTo(AbuseType.INSUFFICIENT_BALANCE_BURST);
        assertThat(consecutiveOnly.evidence().features())
                .containsEntry(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 1L)
                .containsEntry(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 2L);

        List<AbuseRuleMatch> both = evaluator.evaluate(observation, insufficient(3L, 2L));
        assertThat(both).hasSize(1);
        assertThat(both.getFirst().evidence().features()).containsEntry(
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 3L)
                .containsEntry(AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 2L);
    }

    @Test
    void 실패_Burst는_Mission과_Entry의_count_또는_연속_count로_탐지한다() {
        for (AbuseObservationEvent observation : List.of(
                mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE),
                entry("EVENT_NOT_OPEN", ResultClassification.BUSINESS_FAILURE, BalanceScope.common()))) {
            assertThat(evaluator.evaluate(observation, failure(2L, 1L, 1L))).isEmpty();
            for (AbuseFeatureSnapshot snapshot : List.of(
                    failure(3L, 1L, 1L), failure(1L, 2L, 1L), failure(3L, 2L, 1L))) {
                List<AbuseRuleMatch> matches = evaluator.evaluate(observation, snapshot);
                assertThat(matches).hasSize(1);
                AbuseRuleMatch match = matches.getFirst();
                assertThat(match.abuseType()).isEqualTo(AbuseType.FAILURE_BURST);
                assertThat(match.evidence().scope().type()).isEqualTo(DetectionEvidence.Scope.Type.USER);
                assertThat(match.evidence().window().windowMs()).isEqualTo(45_000L);
                assertThat(match.evidence().features()).containsEntry(
                        AbuseMetric.FAILURE_COUNT, snapshot.valueOf(AbuseMetric.FAILURE_COUNT))
                        .containsEntry(AbuseMetric.FAILURE_CONSECUTIVE_COUNT,
                                snapshot.valueOf(AbuseMetric.FAILURE_CONSECUTIVE_COUNT));
                assertThat(match.evidence().thresholds()).containsEntry(AbuseMetric.FAILURE_COUNT, 3L)
                        .containsEntry(AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 2L);
            }
        }
    }

    @Test
    void 실패_Evidence는_후속_복합_Rule을_위한_서로_다른_실패_유형_수를_보존한다() {
        AbuseRuleMatch match = evaluator.evaluate(
                mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE),
                failure(3L, 2L, 4L)).getFirst();

        assertThat(match.evidence().features())
                .containsEntry(AbuseMetric.FAILURE_COUNT, 3L)
                .containsEntry(AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 2L)
                .containsEntry(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 4L);
        assertThat(match.evidence().thresholds())
                .containsEntry(AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 2L);
        assertThat(match.evidence().matchedRules()).isEmpty();
    }

    @Test
    void 서로_다른_실패_유형_수만_높아도_기본_실패_Burst_후보는_만들지_않는다() {
        assertThat(evaluator.evaluate(
                mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE),
                failure(2L, 1L, 100L))).isEmpty();
    }

    @Test
    void 잔액_부족이면서_전역_실패도_임계치에_도달하면_범위와_Window가_다른_두_후보를_반환한다() {
        AbuseFeatureSnapshot snapshot = new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 3L,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 2L,
                AbuseMetric.FAILURE_COUNT, 3L,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 2L,
                AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, 1L));

        List<AbuseRuleMatch> matches = evaluator.evaluate(entry("INSUFFICIENT_BALANCE",
                ResultClassification.BUSINESS_FAILURE, BalanceScope.creator(5L)), snapshot);

        assertThat(matches).extracting(AbuseRuleMatch::abuseType).containsExactly(
                AbuseType.INSUFFICIENT_BALANCE_BURST, AbuseType.FAILURE_BURST);
        assertThat(matches.get(0).evidence().scope().type())
                .isEqualTo(DetectionEvidence.Scope.Type.USER_BALANCE_SCOPE);
        assertThat(matches.get(0).evidence().window().windowMs()).isEqualTo(20_000L);
        assertThat(matches.get(1).evidence().scope().type()).isEqualTo(DetectionEvidence.Scope.Type.USER);
        assertThat(matches.get(1).evidence().window().windowMs()).isEqualTo(45_000L);
    }

    @Test
    void 다른_업무_실패에는_높은_잔액_부족_Feature가_있어도_잔액_부족_Rule을_적용하지_않는다() {
        AbuseFeatureSnapshot snapshot = new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 100L,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 100L,
                AbuseMetric.FAILURE_COUNT, 3L));

        List<AbuseRuleMatch> matches = evaluator.evaluate(
                entry("EVENT_CLOSED", ResultClassification.BUSINESS_FAILURE, BalanceScope.common()), snapshot);
        assertThat(matches).extracting(AbuseRuleMatch::abuseType).containsExactly(AbuseType.FAILURE_BURST);
    }

    @Test
    void 정상_성공_Replay_시스템_실패에는_이전_집계값이_높아도_탐지하지_않는다() {
        AbuseFeatureSnapshot snapshot = new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 100L,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, 100L,
                AbuseMetric.FAILURE_COUNT, 100L,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, 100L));
        for (ResultClassification classification : List.of(ResultClassification.NEW_SUCCESS,
                ResultClassification.REPLAY, ResultClassification.SYSTEM_FAILURE)) {
            assertThat(evaluator.evaluate(entry("INSUFFICIENT_BALANCE", classification,
                    BalanceScope.common()), snapshot)).isEmpty();
            assertThat(evaluator.evaluate(mission("DUPLICATE_MISSION", classification), snapshot)).isEmpty();
        }
    }

    @Test
    void 비활성_정책은_Feature_값이_높아도_빈_결과를_반환한다() {
        FailureBurstRuleEvaluator disabled = new FailureBurstRuleEvaluator(properties(false));
        assertThat(disabled.evaluate(entry("INSUFFICIENT_BALANCE", ResultClassification.BUSINESS_FAILURE,
                BalanceScope.common()), insufficient(100L, 100L))).isEmpty();
    }

    private AbuseObservationEvent entry(
            String resultCode, ResultClassification classification, BalanceScope scope
    ) {
        return entry(5L, resultCode, classification, scope);
    }

    private AbuseObservationEvent entry(
            Long creatorId, String resultCode, ResultClassification classification, BalanceScope scope
    ) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.EVENT_ENTRY,
                UUID.randomUUID(), resultCode, classification, creatorId, 21L, null, null,
                scope, null, NOW, NOW);
    }

    private AbuseObservationEvent mission(String resultCode, ResultClassification classification) {
        return new AbuseObservationEvent(UUID.randomUUID(), 17L, AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(), resultCode, classification, 5L, null, 103L, "2026-10-02",
                BalanceScope.creator(5L), "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", NOW, NOW);
    }

    private AbuseFeatureSnapshot insufficient(long count, long consecutive) {
        return new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, count,
                AbuseMetric.INSUFFICIENT_BALANCE_CONSECUTIVE_COUNT, consecutive));
    }

    private AbuseFeatureSnapshot failure(long count, long consecutive, long distinctTypes) {
        return new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.FAILURE_COUNT, count,
                AbuseMetric.FAILURE_CONSECUTIVE_COUNT, consecutive,
                AbuseMetric.DISTINCT_FAILURE_TYPE_COUNT, distinctTypes));
    }

    private AbuseProperties properties(boolean enabled) {
        Duration window = Duration.ofSeconds(10);
        return new AbuseProperties(enabled,
                new AbuseProperties.CountRule(window, 3),
                new AbuseProperties.CountRule(window, 2),
                new AbuseProperties.CountRule(window, 4),
                new AbuseProperties.ConsecutiveRule(Duration.ofSeconds(20), 3, 2),
                new AbuseProperties.RotationRule(Duration.ofSeconds(30), 3),
                new AbuseProperties.RapidRule(Duration.ofSeconds(2), window, 2),
                new AbuseProperties.FailureRule(Duration.ofSeconds(45), 3, 2, 2));
    }
}

package kr.co.cking.abuse.application.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionEvidence.Scope.Type;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.Test;

class RequestBurstRuleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private final RequestBurstRuleEvaluator evaluator = new RequestBurstRuleEvaluator(properties(true));

    @Test
    void Mission_요청은_임계치에_도달할_때부터_사용자_범위로_탐지한다() {
        AbuseObservationEvent observation = mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS,
                17L, 5L, 103L, "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", "2026-10-02");

        assertThat(evaluator.evaluate(observation, features(AbuseMetric.MISSION_REQUEST_COUNT, 2L))
                .matches()).isEmpty();
        for (long count : new long[] {3L, 4L}) {
            RequestBurstRuleEvaluation result = evaluator.evaluate(
                    observation, features(AbuseMetric.MISSION_REQUEST_COUNT, count));
            assertThat(result.matches()).hasSize(1);
            AbuseRuleMatch match = result.matches().getFirst();
            assertThat(match.abuseType()).isEqualTo(AbuseType.MISSION_REQUEST_BURST);
            assertThat(match.evidence().scope().type()).isEqualTo(Type.USER);
            assertThat(match.evidence().features()).containsEntry(AbuseMetric.MISSION_REQUEST_COUNT, count);
            assertThat(match.evidence().thresholds()).containsEntry(AbuseMetric.MISSION_REQUEST_COUNT, 3L);
            assertThat(match.evidence().window().windowMs()).isEqualTo(10_000L);
        }
    }

    @Test
    void 중복_미션_실패는_Business_Key_범위로만_탐지한다() {
        AbuseObservationEvent creator = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                17L, 5L, 103L, "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", "2026-10-02");
        AbuseObservationEvent common = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                17L, null, 103L, "MISSION:COMMON:DAILY:17:103:2026-10-02", "2026-10-02");

        assertThat(evaluator.evaluate(creator, features(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 1L))
                .matches()).isEmpty();
        for (AbuseObservationEvent observation : new AbuseObservationEvent[] {creator, common}) {
            RequestBurstRuleEvaluation result = evaluator.evaluate(
                    observation, features(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L));
            assertThat(result.matches()).hasSize(1);
            AbuseRuleMatch match = result.matches().getFirst();
            assertThat(match.abuseType()).isEqualTo(AbuseType.DUPLICATE_MISSION_BURST);
            assertThat(match.evidence().scope().type()).isEqualTo(Type.BUSINESS_KEY);
            assertThat(match.evidence().scope().creatorId()).isEqualTo(observation.creatorId());
            assertThat(match.evidence().scope().missionId()).isEqualTo(observation.missionId());
            assertThat(match.evidence().scope().periodKey()).isEqualTo(observation.periodKey());
            assertThat(match.evidence().thresholds())
                    .containsEntry(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L);
        }
        assertThat(evaluator.evaluate(mission("MISSION_INACTIVE", ResultClassification.BUSINESS_FAILURE,
                17L, 5L, 103L, creator.businessKey(), "2026-10-02"),
                features(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 5L)).matches()).isEmpty();
    }

    @Test
    void 같은_Mission도_DAILY_기간이_다르면_탐지_범위가_다르다() {
        AbuseObservationEvent today = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                17L, 5L, 103L, "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", "2026-10-02");
        AbuseObservationEvent tomorrow = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                17L, 5L, 103L, "MISSION:CREATOR:DAILY:17:5:103:2026-10-03", "2026-10-03");
        AbuseFeatureSnapshot threshold = features(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L);

        var todayScope = evaluator.evaluate(today, threshold).matches().getFirst().evidence().scope();
        var tomorrowScope = evaluator.evaluate(tomorrow, threshold).matches().getFirst().evidence().scope();
        assertThat(todayScope).isNotEqualTo(tomorrowScope);
        assertThat(todayScope.periodKey()).isEqualTo("2026-10-02");
        assertThat(tomorrowScope.periodKey()).isEqualTo("2026-10-03");
    }

    @Test
    void Event_요청은_사용자와_Event_범위로_탐지하며_공용_응모권도_허용한다() {
        AbuseObservationEvent creatorEntry = entry(17L, 21L, BalanceScope.creator(5L),
                ResultClassification.NEW_SUCCESS);
        AbuseObservationEvent commonEntry = entry(17L, 22L, BalanceScope.common(),
                ResultClassification.BUSINESS_FAILURE);

        assertThat(evaluator.evaluate(creatorEntry, features(AbuseMetric.ENTRY_REQUEST_COUNT, 3L))
                .matches()).isEmpty();
        for (AbuseObservationEvent observation : new AbuseObservationEvent[] {creatorEntry, commonEntry}) {
            RequestBurstRuleEvaluation result = evaluator.evaluate(
                    observation, features(AbuseMetric.ENTRY_REQUEST_COUNT, 4L));
            assertThat(result.matches()).hasSize(1);
            AbuseRuleMatch match = result.matches().getFirst();
            assertThat(match.abuseType()).isEqualTo(AbuseType.ENTRY_REQUEST_BURST);
            assertThat(match.evidence().scope().type()).isEqualTo(Type.USER_EVENT);
            assertThat(match.evidence().scope().eventId()).isEqualTo(observation.eventId());
            assertThat(match.evidence().scope().balanceScope()).isNull();
            assertThat(match.evidence().thresholds()).containsEntry(AbuseMetric.ENTRY_REQUEST_COUNT, 4L);
        }
    }

    @Test
    void 같은_Event는_응모권_종류가_달라도_동일한_요청_Burst_범위를_쓴다() {
        AbuseFeatureSnapshot threshold = features(AbuseMetric.ENTRY_REQUEST_COUNT, 4L);
        var creatorScope = evaluator.evaluate(entry(17L, 21L, BalanceScope.creator(5L),
                ResultClassification.NEW_SUCCESS), threshold).matches().getFirst().evidence().scope();
        var commonScope = evaluator.evaluate(entry(17L, 21L, BalanceScope.common(),
                ResultClassification.NEW_SUCCESS), threshold).matches().getFirst().evidence().scope();

        assertThat(creatorScope).isEqualTo(commonScope);
    }

    @Test
    void Mission_requestId_회전은_Signal만_반환한다() {
        AbuseObservationEvent share = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                17L, 5L, 103L, "MISSION:CREATOR:ONCE:17:5:103", null);

        assertThat(evaluator.evaluate(share, features(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 2L))
                .signals()).isEmpty();
        RequestBurstRuleEvaluation result = evaluator.evaluate(
                share, features(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L));
        assertThat(result.matches()).isEmpty();
        assertThat(result.signals()).containsExactly(AbuseSignal.REQUEST_ID_ROTATION);
    }

    @Test
    void 회전_Signal이_함께_충족되면_탐지_근거에도_횟수와_임계치를_남긴다() {
        AbuseObservationEvent observation = mission("DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                17L, 5L, 103L, "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", "2026-10-02");
        RequestBurstRuleEvaluation result = evaluator.evaluate(observation, new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.MISSION_REQUEST_COUNT, 3L,
                AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 2L,
                AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L)));

        assertThat(result.matches()).extracting(AbuseRuleMatch::abuseType)
                .containsExactly(AbuseType.MISSION_REQUEST_BURST, AbuseType.DUPLICATE_MISSION_BURST);
        assertThat(result.matches()).allSatisfy(match -> {
            assertThat(match.evidence().signals()).containsExactly(AbuseSignal.REQUEST_ID_ROTATION);
            assertThat(match.evidence().features()).containsEntry(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L);
            assertThat(match.evidence().thresholds()).containsEntry(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L);
        });
    }

    @Test
    void Replay와_System_Failure는_높은_Feature_값이_있어도_판정하지_않는다() {
        for (ResultClassification classification : new ResultClassification[] {
                ResultClassification.REPLAY, ResultClassification.SYSTEM_FAILURE
        }) {
            AbuseFeatureSnapshot snapshot = new AbuseFeatureSnapshot(Map.of(
                    AbuseMetric.MISSION_REQUEST_COUNT, 100L,
                    AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 100L,
                    AbuseMetric.ENTRY_REQUEST_COUNT, 100L,
                    AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 100L));
            assertThat(evaluator.evaluate(mission("DUPLICATE_MISSION", classification,
                    17L, 5L, 103L, "MISSION:CREATOR:ONCE:17:5:103", null), snapshot))
                    .isEqualTo(RequestBurstRuleEvaluation.empty());
            assertThat(evaluator.evaluate(entry(17L, 21L, BalanceScope.creator(5L),
                    classification), snapshot))
                    .isEqualTo(RequestBurstRuleEvaluation.empty());
        }
    }

    @Test
    void Event에는_requestId_회전_Signal을_적용하지_않는다() {
        RequestBurstRuleEvaluation result = evaluator.evaluate(
                entry(17L, 21L, BalanceScope.creator(5L), ResultClassification.NEW_SUCCESS),
                features(AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 100L));

        assertThat(result.matches()).isEmpty();
        assertThat(result.signals()).isEmpty();
    }

    @Test
    void Abuse가_비활성화되면_규칙_판정은_빈_결과다() {
        RequestBurstRuleEvaluator disabled = new RequestBurstRuleEvaluator(properties(false));
        assertThat(disabled.evaluate(mission("EARN_ACCEPTED", ResultClassification.NEW_SUCCESS,
                17L, 5L, 103L, "MISSION:CREATOR:DAILY:17:5:103:2026-10-02", "2026-10-02"),
                features(AbuseMetric.MISSION_REQUEST_COUNT, 100L)))
                .isEqualTo(RequestBurstRuleEvaluation.empty());
    }

    private AbuseObservationEvent mission(
            String resultCode, ResultClassification classification, Long userId,
            Long creatorId, Long missionId, String businessKey, String periodKey
    ) {
        BalanceScope scope = creatorId == null ? BalanceScope.common() : BalanceScope.creator(creatorId);
        return new AbuseObservationEvent(UUID.randomUUID(), userId, AbuseActionType.MISSION_COMPLETE,
                UUID.randomUUID(), resultCode, classification, creatorId, null, missionId, periodKey,
                scope, businessKey, NOW, NOW);
    }

    private AbuseObservationEvent entry(
            Long userId, Long eventId, BalanceScope scope, ResultClassification classification
    ) {
        return new AbuseObservationEvent(UUID.randomUUID(), userId, AbuseActionType.EVENT_ENTRY,
                UUID.randomUUID(), "SUCCESS", classification, 5L, eventId, null, null,
                scope, null, NOW, NOW);
    }

    private AbuseFeatureSnapshot features(AbuseMetric metric, long value) {
        return new AbuseFeatureSnapshot(Map.of(metric, value));
    }

    private AbuseProperties properties(boolean enabled) {
        Duration window = Duration.ofSeconds(10);
        return new AbuseProperties(enabled,
                new AbuseProperties.CountRule(window, 3),
                new AbuseProperties.CountRule(window, 2),
                new AbuseProperties.CountRule(window, 4),
                new AbuseProperties.ConsecutiveRule(window, 3, 2),
                new AbuseProperties.RotationRule(window, 3),
                new AbuseProperties.RapidRule(Duration.ofSeconds(2), window, 2),
                new AbuseProperties.FailureRule(window, 3, 2, 2));
    }
}

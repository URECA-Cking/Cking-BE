package kr.co.cking.abuse.application.rule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.DetectionEvidence.Scope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.mission.domain.MissionErrorCode;
import org.springframework.stereotype.Component;

/** Feature Store가 계산한 값으로 요청 Burst 3종과 Mission requestId Rotation을 판정한다. */
@Component
public class RequestBurstRuleEvaluator {

    private final AbuseProperties properties;

    public RequestBurstRuleEvaluator(AbuseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
    }

    public RequestBurstRuleEvaluation evaluate(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
        if (!properties.enabled() || observation.resultClassification() == ResultClassification.REPLAY
                || observation.resultClassification() == ResultClassification.SYSTEM_FAILURE) {
            return RequestBurstRuleEvaluation.empty();
        }
        properties.windowPolicy(); // 활성화된 설정의 완전성을 검증한다.

        return observation.actionType() == AbuseActionType.MISSION_COMPLETE
                ? evaluateMission(observation, snapshot)
                : evaluateEntry(observation, snapshot);
    }

    private RequestBurstRuleEvaluation evaluateMission(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        List<AbuseRuleMatch> matches = new ArrayList<>();
        boolean rotation = snapshot.valueOf(AbuseMetric.DISTINCT_REQUEST_ID_COUNT)
                >= properties.requestIdRotation().distinctThreshold();
        Map<AbuseSignal, DetectionEvidence> signalEvidence = rotation
                ? Map.of(AbuseSignal.REQUEST_ID_ROTATION, rotationEvidence(observation, snapshot))
                : Map.of();

        if (snapshot.valueOf(AbuseMetric.MISSION_REQUEST_COUNT)
                >= properties.missionRequestBurst().threshold()) {
            matches.add(match(
                    AbuseType.MISSION_REQUEST_BURST,
                    new Scope(Scope.Type.USER, null, null, null, null, null),
                    properties.missionRequestBurst().window(),
                    AbuseMetric.MISSION_REQUEST_COUNT,
                    snapshot,
                    properties.missionRequestBurst().threshold()
            ));
        }

        if (MissionErrorCode.DUPLICATE_MISSION.name().equals(observation.resultCode())
                && observation.resultClassification() == ResultClassification.BUSINESS_FAILURE
                && snapshot.valueOf(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT)
                        >= properties.duplicateMissionBurst().threshold()) {
            matches.add(match(
                    AbuseType.DUPLICATE_MISSION_BURST,
                    new Scope(Scope.Type.BUSINESS_KEY, observation.creatorId(), null,
                            observation.missionId(), observation.periodKey(), observation.balanceScope()),
                    properties.duplicateMissionBurst().window(),
                    AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT,
                    snapshot,
                    properties.duplicateMissionBurst().threshold()
            ));
        }

        return new RequestBurstRuleEvaluation(matches, signalEvidence);
    }

    private RequestBurstRuleEvaluation evaluateEntry(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        if (snapshot.valueOf(AbuseMetric.ENTRY_REQUEST_COUNT)
                < properties.entryRequestBurst().threshold()) {
            return RequestBurstRuleEvaluation.empty();
        }
        return new RequestBurstRuleEvaluation(List.of(match(
                AbuseType.ENTRY_REQUEST_BURST,
                new Scope(Scope.Type.USER_EVENT, null, observation.eventId(),
                        null, null, null),
                properties.entryRequestBurst().window(),
                AbuseMetric.ENTRY_REQUEST_COUNT,
                snapshot,
                properties.entryRequestBurst().threshold()
        )), Map.of());
    }

    private DetectionEvidence rotationEvidence(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot
    ) {
        AbuseMetric metric = AbuseMetric.DISTINCT_REQUEST_ID_COUNT;
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new Scope(Scope.Type.BUSINESS_KEY, observation.creatorId(), null,
                        observation.missionId(), observation.periodKey(), observation.balanceScope()),
                new DetectionEvidence.Window(properties.requestIdRotation().window().toMillis(), null),
                Map.of(metric, snapshot.valueOf(metric)),
                Map.of(metric, properties.requestIdRotation().distinctThreshold().longValue()),
                Set.of(AbuseSignal.REQUEST_ID_ROTATION),
                Set.of()
        );
    }

    private AbuseRuleMatch match(
            AbuseType type,
            Scope scope,
            Duration window,
            AbuseMetric metric,
            AbuseFeatureSnapshot snapshot,
            long threshold
    ) {
        DetectionEvidence evidence = new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                scope,
                new DetectionEvidence.Window(window.toMillis(), null),
                Map.of(metric, snapshot.valueOf(metric)),
                Map.of(metric, threshold),
                Set.of(),
                Set.of()
        );
        return new AbuseRuleMatch(type, evidence);
    }
}

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
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.DetectionEvidence.Scope;
import kr.co.cking.abuse.domain.ResultClassification;
import org.springframework.stereotype.Component;

/** YouTube 구독 인증의 제출·이미지 재사용·음성 판정만 별도 임계치로 평가한다. */
@Component
public class YoutubeSubscriptionRuleEvaluator {

    private final AbuseProperties properties;

    public YoutubeSubscriptionRuleEvaluator(AbuseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
    }

    public List<AbuseRuleMatch> evaluate(AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
        if (!properties.youtubeSubscriptionEnabled()
                || observation.actionType() != AbuseActionType.YOUTUBE_SUBSCRIPTION
                || observation.resultClassification() == ResultClassification.REPLAY
                || observation.resultClassification() == ResultClassification.SYSTEM_FAILURE) {
            return List.of();
        }

        AbuseProperties.YoutubeSubscriptionRule rule = properties.youtubeSubscription();
        List<AbuseRuleMatch> matches = new ArrayList<>();
        if ("SUBMISSION_CREATED".equals(observation.resultCode())
                && snapshot.valueOf(AbuseMetric.YOUTUBE_SUBMISSION_COUNT) >= rule.submissionBurst().threshold()) {
            matches.add(match(AbuseType.YOUTUBE_SUBMISSION_BURST, userScope(), rule.submissionBurst().window(),
                    AbuseMetric.YOUTUBE_SUBMISSION_COUNT, snapshot, rule.submissionBurst().threshold()));
        }
        if (isSubmissionRequest(observation)
                && snapshot.valueOf(AbuseMetric.DISTINCT_REQUEST_ID_COUNT) >= rule.requestIdRotation().distinctThreshold()) {
            matches.add(match(AbuseType.YOUTUBE_REQUEST_ID_ROTATION, businessKeyScope(observation),
                    rule.requestIdRotation().window(), AbuseMetric.DISTINCT_REQUEST_ID_COUNT, snapshot,
                    rule.requestIdRotation().distinctThreshold()));
        }
        if ("IMAGE_REUSE".equals(observation.resultCode())
                && snapshot.valueOf(AbuseMetric.YOUTUBE_IMAGE_REUSE_COUNT) >= rule.imageReuse().threshold()) {
            matches.add(match(AbuseType.YOUTUBE_IMAGE_REUSE, businessKeyScope(observation), rule.imageReuse().window(),
                    AbuseMetric.YOUTUBE_IMAGE_REUSE_COUNT, snapshot, rule.imageReuse().threshold()));
        }
        long negativeCount = snapshot.valueOf(AbuseMetric.YOUTUBE_NEGATIVE_DECISION_COUNT);
        long negativeConsecutive = snapshot.valueOf(AbuseMetric.YOUTUBE_NEGATIVE_DECISION_CONSECUTIVE_COUNT);
        if (isNegativeDecision(observation)
                && (negativeCount >= rule.negativeDecisionBurst().threshold()
                || negativeConsecutive >= rule.negativeDecisionBurst().consecutiveThreshold())) {
            matches.add(negativeDecisionMatch(observation, snapshot, rule.negativeDecisionBurst()));
        }
        return List.copyOf(matches);
    }

    private boolean isSubmissionRequest(AbuseObservationEvent observation) {
        return !"IMAGE_REUSE".equals(observation.resultCode())
                && !"APPROVED".equals(observation.resultCode())
                && !isNegativeDecision(observation);
    }

    private boolean isNegativeDecision(AbuseObservationEvent observation) {
        return "REJECTED".equals(observation.resultCode())
                || "RETRY_REQUIRED".equals(observation.resultCode());
    }

    private AbuseRuleMatch negativeDecisionMatch(
            AbuseObservationEvent observation, AbuseFeatureSnapshot snapshot, AbuseProperties.ConsecutiveRule rule
    ) {
        return new AbuseRuleMatch(AbuseType.YOUTUBE_NEGATIVE_DECISION_BURST, new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION, businessKeyScope(observation),
                new DetectionEvidence.Window(rule.window().toMillis(), null),
                Map.of(
                        AbuseMetric.YOUTUBE_NEGATIVE_DECISION_COUNT,
                        snapshot.valueOf(AbuseMetric.YOUTUBE_NEGATIVE_DECISION_COUNT),
                        AbuseMetric.YOUTUBE_NEGATIVE_DECISION_CONSECUTIVE_COUNT,
                        snapshot.valueOf(AbuseMetric.YOUTUBE_NEGATIVE_DECISION_CONSECUTIVE_COUNT)),
                Map.of(
                        AbuseMetric.YOUTUBE_NEGATIVE_DECISION_COUNT, rule.threshold().longValue(),
                        AbuseMetric.YOUTUBE_NEGATIVE_DECISION_CONSECUTIVE_COUNT,
                        rule.consecutiveThreshold().longValue()),
                Set.of(), Set.of()));
    }

    private AbuseRuleMatch match(
            AbuseType type, Scope scope, Duration window, AbuseMetric metric,
            AbuseFeatureSnapshot snapshot, long threshold
    ) {
        return new AbuseRuleMatch(type, new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION, scope, new DetectionEvidence.Window(window.toMillis(), null),
                Map.of(metric, snapshot.valueOf(metric)), Map.of(metric, threshold), Set.of(), Set.of()));
    }

    private Scope userScope() {
        return new Scope(Scope.Type.USER, null, null, null, null, null);
    }

    private Scope businessKeyScope(AbuseObservationEvent observation) {
        return new Scope(Scope.Type.BUSINESS_KEY, observation.creatorId(), null,
                observation.missionId(), null, observation.balanceScope());
    }
}

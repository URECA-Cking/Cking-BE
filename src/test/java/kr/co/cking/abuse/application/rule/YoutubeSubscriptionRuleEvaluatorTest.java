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
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import org.junit.jupiter.api.Test;

class YoutubeSubscriptionRuleEvaluatorTest {

    @Test
    void 구독_인증_규칙은_제출_Rotation_재사용_음성_판정을_각각_분리해_탐지한다() {
        YoutubeSubscriptionRuleEvaluator evaluator = new YoutubeSubscriptionRuleEvaluator(properties());
        AbuseFeatureSnapshot snapshot = new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.YOUTUBE_SUBMISSION_COUNT, 2L,
                AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 2L,
                AbuseMetric.YOUTUBE_IMAGE_REUSE_COUNT, 2L,
                AbuseMetric.YOUTUBE_NEGATIVE_DECISION_COUNT, 2L,
                AbuseMetric.YOUTUBE_NEGATIVE_DECISION_CONSECUTIVE_COUNT, 2L));

        assertThat(evaluator.evaluate(observation("IMAGE_REUSE"), snapshot))
                .extracting(AbuseRuleMatch::abuseType)
                .containsExactlyInAnyOrder(
                        AbuseType.YOUTUBE_SUBMISSION_BURST,
                        AbuseType.YOUTUBE_REQUEST_ID_ROTATION,
                        AbuseType.YOUTUBE_IMAGE_REUSE,
                        AbuseType.YOUTUBE_NEGATIVE_DECISION_BURST);
    }

    @Test
    void 재전송과_시스템_실패는_새_구독_인증_Detection을_만들지_않는다() {
        YoutubeSubscriptionRuleEvaluator evaluator = new YoutubeSubscriptionRuleEvaluator(properties());
        AbuseFeatureSnapshot snapshot = new AbuseFeatureSnapshot(Map.of(
                AbuseMetric.YOUTUBE_SUBMISSION_COUNT, 99L,
                AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 99L));

        assertThat(evaluator.evaluate(observation("SUBMISSION_REPLAY", ResultClassification.REPLAY), snapshot)).isEmpty();
        assertThat(evaluator.evaluate(observation("SYSTEM_ERROR", ResultClassification.SYSTEM_FAILURE), snapshot)).isEmpty();
    }

    private AbuseObservationEvent observation(String code) {
        return observation(code, ResultClassification.BUSINESS_FAILURE);
    }

    private AbuseObservationEvent observation(String code, ResultClassification classification) {
        return new AbuseObservationEvent(UUID.randomUUID(), 7L, AbuseActionType.YOUTUBE_SUBSCRIPTION,
                UUID.randomUUID(), code, classification, 42L, null, 103L, null, BalanceScope.creator(42L),
                "MISSION:CREATOR:ONCE:7:42:103", Instant.EPOCH, Instant.EPOCH);
    }

    private AbuseProperties properties() {
        Duration window = Duration.ofMinutes(1);
        AbuseProperties.CountRule count = new AbuseProperties.CountRule(window, 2);
        return new AbuseProperties(true, count, count, count,
                new AbuseProperties.ConsecutiveRule(window, 2, 2),
                new AbuseProperties.RotationRule(window, 2),
                new AbuseProperties.RapidRule(window, window, 2),
                new AbuseProperties.FailureRule(window, 2, 2, 2),
                new AbuseProperties.YoutubeSubscriptionRule(true, count,
                        new AbuseProperties.RotationRule(window, 2), count,
                        new AbuseProperties.ConsecutiveRule(window, 2, 2)));
    }
}

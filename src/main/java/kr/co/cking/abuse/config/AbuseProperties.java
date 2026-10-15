package kr.co.cking.abuse.config;

import jakarta.validation.constraints.AssertTrue;
import kr.co.cking.abuse.application.model.AbuseFeatureWindowPolicy;
import kr.co.cking.abuse.domain.AbuseType;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Abuse Detection의 활성 여부와 Calibration 결과를 주입받는 설정 계약이다. */
@Validated
@ConfigurationProperties(prefix = "cking.abuse")
public record AbuseProperties(
        boolean enabled,
        CountRule missionRequestBurst,
        CountRule duplicateMissionBurst,
        CountRule entryRequestBurst,
        ConsecutiveRule insufficientBalanceBurst,
        RotationRule requestIdRotation,
        RapidRule rapidEarnAndSpend,
        FailureRule failureBurst,
        YoutubeSubscriptionRule youtubeSubscription
) {

    /**
     * 기존 호환 생성자가 함께 있어도 Spring이 설정 바인딩에 사용할 canonical 생성자를 명시한다.
     */
    @ConstructorBinding
    public AbuseProperties {
    }

    public static final Duration TTL_PADDING = Duration.ofSeconds(60);
    public static final int COOLDOWN_WINDOW_MULTIPLIER = 2;
    private static final YoutubeSubscriptionRule DISABLED_YOUTUBE_SUBSCRIPTION =
            new YoutubeSubscriptionRule(false, null, null, null, null);

    /** 기존 v1 설정 생성 코드를 유지하면서 구독 인증 관측은 명시적으로 비활성화한다. */
    public AbuseProperties(
            boolean enabled,
            CountRule missionRequestBurst,
            CountRule duplicateMissionBurst,
            CountRule entryRequestBurst,
            ConsecutiveRule insufficientBalanceBurst,
            RotationRule requestIdRotation,
            RapidRule rapidEarnAndSpend,
            FailureRule failureBurst
    ) {
        this(enabled, missionRequestBurst, duplicateMissionBurst, entryRequestBurst,
                insufficientBalanceBurst, requestIdRotation, rapidEarnAndSpend, failureBurst,
                DISABLED_YOUTUBE_SUBSCRIPTION);
    }

    /** 비활성화 상태는 하위 설정을 요구하지 않고, 활성화 상태만 전체 설정을 검증한다. */
    @AssertTrue(message = "cking.abuse.enabled=true이면 모든 window와 threshold는 양수로 설정해야 합니다.")
    public boolean isValidForEnabledDetection() {
        if (!enabled) {
            return true;
        }
        return valid(missionRequestBurst)
                && valid(duplicateMissionBurst)
                && valid(entryRequestBurst)
                && valid(insufficientBalanceBurst)
                && valid(requestIdRotation)
                && valid(rapidEarnAndSpend)
                && valid(failureBurst)
                && (!youtubeSubscriptionEnabled() || valid(youtubeSubscription));
    }

    /** Feature Store에 Threshold 없이 Window 계약만 전달한다. */
    public AbuseFeatureWindowPolicy windowPolicy() {
        requireEnabledConfiguration();
        return new AbuseFeatureWindowPolicy(
                missionRequestBurst.window(),
                duplicateMissionBurst.window(),
                entryRequestBurst.window(),
                insufficientBalanceBurst.window(),
                requestIdRotation.window(),
                rapidEarnAndSpend.window(),
                rapidEarnAndSpend.maxDelay(),
                failureBurst.window(),
                youtubeSubscriptionWindow(),
                youtubeRequestIdRotationWindow(),
                youtubeImageReuseWindow(),
                youtubeNegativeDecisionWindow()
        );
    }

    /** Detection 유형의 primary window 두 배를 Cooldown TTL로 사용한다. */
    public Duration cooldownTtl(AbuseType abuseType) {
        requireEnabledConfiguration();
        Duration primaryWindow = switch (abuseType) {
            case MISSION_REQUEST_BURST -> missionRequestBurst.window();
            case DUPLICATE_MISSION_BURST -> duplicateMissionBurst.window();
            case ENTRY_REQUEST_BURST -> entryRequestBurst.window();
            case INSUFFICIENT_BALANCE_BURST -> insufficientBalanceBurst.window();
            case RAPID_EARN_AND_SPEND -> rapidEarnAndSpend.window();
            case FAILURE_BURST -> failureBurst.window();
            case YOUTUBE_SUBMISSION_BURST -> youtubeSubscriptionWindow();
            case YOUTUBE_REQUEST_ID_ROTATION -> youtubeRequestIdRotationWindow();
            case YOUTUBE_IMAGE_REUSE -> youtubeImageReuseWindow();
            case YOUTUBE_NEGATIVE_DECISION_BURST -> youtubeNegativeDecisionWindow();
        };
        return primaryWindow.multipliedBy(COOLDOWN_WINDOW_MULTIPLIER);
    }

    private void requireEnabledConfiguration() {
        if (!enabled || !isValidForEnabledDetection()) {
            throw new IllegalStateException("활성화된 Abuse Detection 설정이 완전하지 않습니다.");
        }
    }

    private static boolean valid(CountRule rule) {
        return rule != null && positive(rule.window()) && positive(rule.threshold());
    }

    private static boolean valid(ConsecutiveRule rule) {
        return rule != null
                && positive(rule.window())
                && positive(rule.threshold())
                && positive(rule.consecutiveThreshold());
    }

    private static boolean valid(RotationRule rule) {
        return rule != null && positive(rule.window()) && positive(rule.distinctThreshold());
    }

    private static boolean valid(RapidRule rule) {
        return rule != null
                && positive(rule.maxDelay())
                && positive(rule.window())
                && positive(rule.threshold());
    }

    private static boolean valid(FailureRule rule) {
        return rule != null
                && positive(rule.window())
                && positive(rule.threshold())
                && positive(rule.consecutiveThreshold())
                && positive(rule.distinctTypeThreshold());
    }

    private static boolean valid(YoutubeSubscriptionRule rule) {
        return rule != null
                && valid(rule.submissionBurst())
                && valid(rule.requestIdRotation())
                && valid(rule.imageReuse())
                && valid(rule.negativeDecisionBurst());
    }

    /** 구독 인증 Rule은 전역 Abuse와 별도로 점진적으로 켤 수 있다. */
    public boolean youtubeSubscriptionEnabled() {
        return enabled && youtubeSubscription != null && youtubeSubscription.enabled();
    }

    private Duration youtubeSubscriptionWindow() {
        return youtubeSubscriptionEnabled() ? youtubeSubscription.submissionBurst().window() : Duration.ofMinutes(1);
    }

    private Duration youtubeImageReuseWindow() {
        return youtubeSubscriptionEnabled() ? youtubeSubscription.imageReuse().window() : Duration.ofMinutes(1);
    }

    private Duration youtubeRequestIdRotationWindow() {
        return youtubeSubscriptionEnabled()
                ? youtubeSubscription.requestIdRotation().window() : Duration.ofMinutes(1);
    }

    private Duration youtubeNegativeDecisionWindow() {
        return youtubeSubscriptionEnabled() ? youtubeSubscription.negativeDecisionBurst().window() : Duration.ofMinutes(1);
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    public record CountRule(Duration window, Integer threshold) {
    }

    public record ConsecutiveRule(
            Duration window,
            Integer threshold,
            Integer consecutiveThreshold
    ) {
    }

    public record RotationRule(Duration window, Integer distinctThreshold) {
    }

    public record RapidRule(
            Duration maxDelay,
            Duration window,
            Integer threshold
    ) {
    }

    public record FailureRule(
            Duration window,
            Integer threshold,
            Integer consecutiveThreshold,
            Integer distinctTypeThreshold
    ) {
    }

    /** YouTube 구독 인증 전용 Rule 설정. disabled일 때 하위 값은 요구하지 않는다. */
    public record YoutubeSubscriptionRule(
            boolean enabled,
            CountRule submissionBurst,
            RotationRule requestIdRotation,
            CountRule imageReuse,
            ConsecutiveRule negativeDecisionBurst
    ) {
    }
}

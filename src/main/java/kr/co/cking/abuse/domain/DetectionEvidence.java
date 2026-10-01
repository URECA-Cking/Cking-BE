package kr.co.cking.abuse.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 탐지 시점의 범위·Feature·Threshold를 재현 가능하게 보존하는 근거다. */
public record DetectionEvidence(
        String policyVersion,
        Scope scope,
        Window window,
        Map<AbuseMetric, Long> features,
        Map<AbuseMetric, Long> thresholds,
        Set<AbuseSignal> signals,
        Set<AbuseCompositeRule> matchedRules
) {

    public static final String POLICY_VERSION = "ABUSE_V1";

    public DetectionEvidence {
        if (!POLICY_VERSION.equals(policyVersion)) {
            throw new IllegalArgumentException("지원하지 않는 Abuse 정책 버전입니다.");
        }
        Objects.requireNonNull(scope, "scope는 필수입니다.");
        Objects.requireNonNull(window, "window는 필수입니다.");
        features = immutableMetricMap(features, "features");
        thresholds = immutableMetricMap(thresholds, "thresholds");
        signals = Set.copyOf(Objects.requireNonNull(signals, "signals는 필수입니다."));
        matchedRules = Set.copyOf(Objects.requireNonNull(matchedRules, "matchedRules는 필수입니다."));
    }

    private static Map<AbuseMetric, Long> immutableMetricMap(
            Map<AbuseMetric, Long> source,
            String name
    ) {
        Objects.requireNonNull(source, name + "는 필수입니다.");
        source.forEach((metric, value) -> {
            Objects.requireNonNull(metric, name + "의 metric은 필수입니다.");
            if (value == null || value < 0) {
                throw new IllegalArgumentException(name + " 값은 0 이상이어야 합니다.");
            }
        });
        return Map.copyOf(source);
    }

    public record Scope(
            Type type,
            Long creatorId,
            Long eventId,
            Long missionId,
            String periodKey,
            BalanceScope balanceScope
    ) {
        public enum Type {
            USER,
            BUSINESS_KEY,
            USER_EVENT,
            USER_BALANCE_SCOPE
        }

        public Scope {
            Objects.requireNonNull(type, "scope type은 필수입니다.");
            if (creatorId != null) {
                requirePositive(creatorId, "creatorId");
            }
            if (eventId != null) {
                requirePositive(eventId, "eventId");
            }
            if (missionId != null) {
                requirePositive(missionId, "missionId");
            }
        }
    }

    public record Window(long windowMs, Long maxDelayMs) {
        public Window {
            if (windowMs <= 0) {
                throw new IllegalArgumentException("windowMs는 양수여야 합니다.");
            }
            if (maxDelayMs != null && maxDelayMs <= 0) {
                throw new IllegalArgumentException("maxDelayMs는 양수여야 합니다.");
            }
        }
    }
}

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
        features = immutableMetricMap(features, "features", true);
        thresholds = immutableMetricMap(thresholds, "thresholds", false);
        signals = Set.copyOf(Objects.requireNonNull(signals, "signals는 필수입니다."));
        matchedRules = Set.copyOf(Objects.requireNonNull(matchedRules, "matchedRules는 필수입니다."));
    }

    private static Map<AbuseMetric, Long> immutableMetricMap(
            Map<AbuseMetric, Long> source,
            String name,
            boolean allowZero
    ) {
        Objects.requireNonNull(source, name + "는 필수입니다.");
        source.forEach((metric, value) -> {
            Objects.requireNonNull(metric, name + "의 metric은 필수입니다.");
            if (value == null || value < 0 || (!allowZero && value == 0)) {
                throw new IllegalArgumentException(
                        allowZero ? name + " 값은 0 이상이어야 합니다." : name + " 값은 양수여야 합니다.");
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
            if (periodKey != null && periodKey.isBlank()) {
                throw new IllegalArgumentException("periodKey는 값이 있으면 비어 있을 수 없습니다.");
            }

            switch (type) {
                case USER -> requireAbsent(
                        creatorId, eventId, missionId, periodKey, balanceScope,
                        "USER scope에는 업무 식별자를 지정할 수 없습니다.");
                case BUSINESS_KEY -> {
                    requirePositive(missionId, "BUSINESS_KEY scope의 missionId");
                    Objects.requireNonNull(
                            balanceScope,
                            "BUSINESS_KEY scope의 balanceScope는 필수입니다.");
                    if (eventId != null) {
                        throw new IllegalArgumentException(
                                "BUSINESS_KEY scope에는 eventId를 지정할 수 없습니다.");
                    }
                    validateMissionBalanceScope(creatorId, balanceScope);
                }
                case USER_EVENT -> {
                    requirePositive(eventId, "USER_EVENT scope의 eventId");
                    if (missionId != null || periodKey != null) {
                        throw new IllegalArgumentException(
                                "USER_EVENT scope에는 missionId와 periodKey를 지정할 수 없습니다.");
                    }
                    validateEventBalanceScope(creatorId, balanceScope);
                }
                case USER_BALANCE_SCOPE -> {
                    Objects.requireNonNull(
                            balanceScope,
                            "USER_BALANCE_SCOPE의 balanceScope는 필수입니다.");
                    if (eventId != null || missionId != null || periodKey != null) {
                        throw new IllegalArgumentException(
                                "USER_BALANCE_SCOPE에는 eventId, missionId, periodKey를 지정할 수 없습니다.");
                    }
                    validateMissionBalanceScope(creatorId, balanceScope);
                }
            }
        }

        private static void requireAbsent(
                Long creatorId,
                Long eventId,
                Long missionId,
                String periodKey,
                BalanceScope balanceScope,
                String message
        ) {
            if (creatorId != null
                    || eventId != null
                    || missionId != null
                    || periodKey != null
                    || balanceScope != null) {
                throw new IllegalArgumentException(message);
            }
        }

        private static void validateMissionBalanceScope(
                Long creatorId,
                BalanceScope balanceScope
        ) {
            if (balanceScope.type() == BalanceScope.Type.COMMON && creatorId != null) {
                throw new IllegalArgumentException("COMMON balanceScope에는 creatorId를 지정할 수 없습니다.");
            }
            if (balanceScope.type() == BalanceScope.Type.CREATOR
                    && !balanceScope.creatorId().equals(creatorId)) {
                throw new IllegalArgumentException("creatorId와 balanceScope가 일치해야 합니다.");
            }
        }

        private static void validateEventBalanceScope(
                Long creatorId,
                BalanceScope balanceScope
        ) {
            if (balanceScope != null
                    && balanceScope.type() == BalanceScope.Type.CREATOR
                    && !balanceScope.creatorId().equals(creatorId)) {
                throw new IllegalArgumentException("creatorId와 balanceScope가 일치해야 합니다.");
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

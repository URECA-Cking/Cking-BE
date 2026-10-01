package kr.co.cking.abuse.application.model;

import kr.co.cking.abuse.domain.AbuseMetric;

import java.util.Map;
import java.util.Objects;

/** 한 Observation의 원자 갱신 직후 Rule Engine에 제공하는 Feature 값이다. */
public record AbuseFeatureSnapshot(Map<AbuseMetric, Long> values) {

    public AbuseFeatureSnapshot {
        Objects.requireNonNull(values, "values는 필수입니다.");
        values.forEach((metric, value) -> {
            Objects.requireNonNull(metric, "metric은 필수입니다.");
            if (value == null || value < 0) {
                throw new IllegalArgumentException("Feature 값은 0 이상이어야 합니다.");
            }
        });
        values = Map.copyOf(values);
    }

    public long valueOf(AbuseMetric metric) {
        Objects.requireNonNull(metric, "metric은 필수입니다.");
        return values.getOrDefault(metric, 0L);
    }
}

package kr.co.cking.abuse.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Rule 하나가 임계치를 충족했을 때 Persistence 경계로 전달하는 결과다. */
public record DetectionResult(
        AbuseType abuseType,
        String cooldownScopeHash,
        Instant detectedAt,
        DetectionEvidence evidence
) {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public DetectionResult {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        if (cooldownScopeHash == null || !SHA256.matcher(cooldownScopeHash).matches()) {
            throw new IllegalArgumentException("cooldownScopeHash는 lowercase SHA-256 hex여야 합니다.");
        }
        Objects.requireNonNull(detectedAt, "detectedAt은 필수입니다.");
        Objects.requireNonNull(evidence, "evidence는 필수입니다.");
    }
}

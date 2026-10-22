package kr.co.cking.abuse.domain;

import java.time.Instant;
import java.util.Objects;

/** Rule 하나가 임계치를 충족했을 때 Persistence 경계로 전달하는 결과다. */
public record DetectionResult(
        AbuseType abuseType,
        String cooldownScopeHash,
        Instant detectedAt,
        DetectionEvidence evidence
) {
    public DetectionResult {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        AbuseScopeHash.requireValidHash(cooldownScopeHash, "cooldownScopeHash");
        Objects.requireNonNull(detectedAt, "detectedAt은 필수입니다.");
        Objects.requireNonNull(evidence, "evidence는 필수입니다.");
    }
}

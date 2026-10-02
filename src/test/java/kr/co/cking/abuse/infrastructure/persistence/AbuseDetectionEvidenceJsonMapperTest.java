package kr.co.cking.abuse.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Persistence Evidence JSON이 문서 계약의 필드명과 Rule 표현을 따르는지 검증한다. */
class AbuseDetectionEvidenceJsonMapperTest {

    /** Metric은 lowerCamelCase, 복합 Rule은 하이픈 형식으로 직렬화하고 Domain으로 왕복 복원한다. */
    @Test
    void 문서화된_Evidence_JSON_형식으로_직렬화하고_복원한다() {
        AbuseDetectionEvidenceJsonMapper mapper = new AbuseDetectionEvidenceJsonMapper(new ObjectMapper());
        DetectionEvidence evidence = businessKeyEvidence();

        String json = mapper.toJson(evidence);

        assertThat(json)
                .contains("duplicateMissionFailureCount", "distinctRequestIdCountPerBusinessKey", "RULE-01")
                .doesNotContain("DUPLICATE_MISSION_FAILURE_COUNT", "DISTINCT_REQUEST_ID_COUNT", "RULE_01");
        assertThat(mapper.fromJson(json)).isEqualTo(evidence);
    }

    /** 문서 예시에 맞는 BUSINESS_KEY Scope Evidence를 생성한다. */
    private DetectionEvidence businessKeyEvidence() {
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.BUSINESS_KEY,
                        10L, null, 3L, "2026-10-01", BalanceScope.creator(10L)),
                new DetectionEvidence.Window(10_000L, null),
                Map.of(
                        AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 8L,
                        AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 6L),
                Map.of(
                        AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 5L,
                        AbuseMetric.DISTINCT_REQUEST_ID_COUNT, 3L),
                Set.of(AbuseSignal.REQUEST_ID_ROTATION),
                Set.of(AbuseCompositeRule.RULE_01));
    }
}

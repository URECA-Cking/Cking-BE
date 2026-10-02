package kr.co.cking.abuse.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

    /** 네 Scope와 공용·Creator 잔액 범위, 빈 Map·Set도 손실 없이 JSON 왕복한다. */
    @Test
    void 모든_Scope와_컬렉션_형태를_JSON으로_왕복한다() {
        AbuseDetectionEvidenceJsonMapper mapper = new AbuseDetectionEvidenceJsonMapper(new ObjectMapper());
        List<DetectionEvidence> evidenceValues = List.of(
                userEvidence(),
                businessKeyEvidence(),
                userEventEvidence(),
                userBalanceScopeEvidence());

        evidenceValues.forEach(evidence -> assertThat(mapper.fromJson(mapper.toJson(evidence))).isEqualTo(evidence));
        assertThat(mapper.toJson(userEvidence())).contains("\"features\":{}", "\"signals\":[]");
    }

    /** Scope와 Window는 해당하지 않는 선택 필드를 JSON에 남기지 않는다. */
    @Test
    void 선택_Scope_식별자와_maxDelayMs는_null이면_JSON에서_생략한다() {
        AbuseDetectionEvidenceJsonMapper mapper = new AbuseDetectionEvidenceJsonMapper(new ObjectMapper());

        String userJson = mapper.toJson(userEvidence());
        String businessKeyJson = mapper.toJson(businessKeyEvidence());
        String userEventJson = mapper.toJson(userEventEvidence());

        assertThat(userJson)
                .doesNotContain("creatorId", "eventId", "missionId", "periodKey", "balanceScope", "maxDelayMs");
        assertThat(businessKeyJson).doesNotContain("eventId", "maxDelayMs");
        assertThat(userEventJson)
                .doesNotContain("missionId", "periodKey")
                .contains(
                        "\"creatorId\":10",
                        "\"maxDelayMs\":3000",
                        "\"balanceScope\":{\"type\":\"COMMON\",\"creatorId\":null}");
    }

    /** 필수 JSON 객체가 누락되어도 mapper 경계의 일관된 예외로 변환한다. */
    @Test
    void 필수_Evidence_객체가_누락되면_일관된_예외로_변환한다() {
        AbuseDetectionEvidenceJsonMapper mapper = new AbuseDetectionEvidenceJsonMapper(new ObjectMapper());
        String missingScopeJson = """
                {"policyVersion":"ABUSE_V1","window":{"windowMs":10000},"features":{},"thresholds":{},"signals":[],
                "matchedRules":[]}
                """;

        assertThatThrownBy(() -> mapper.fromJson(missingScopeJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DetectionEvidence JSON을 복원할 수 없습니다.")
                .hasCauseInstanceOf(NullPointerException.class);
    }

    /** 업무 식별자를 갖지 않고 빈 Feature·Signal·Rule 컬렉션을 가진 USER Scope를 생성한다. */
    private DetectionEvidence userEvidence() {
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.USER,
                        null, null, null, null, null),
                new DetectionEvidence.Window(10_000L, null),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of());
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

    /** COMMON 응모권을 사용하는 Event의 USER_EVENT Scope를 생성한다. */
    private DetectionEvidence userEventEvidence() {
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.USER_EVENT,
                        10L, 20L, null, null, BalanceScope.common()),
                new DetectionEvidence.Window(10_000L, 3_000L),
                Map.of(AbuseMetric.ENTRY_REQUEST_COUNT, 14L),
                Map.of(AbuseMetric.ENTRY_REQUEST_COUNT, 8L),
                Set.of(),
                Set.of(AbuseCompositeRule.RULE_03));
    }

    /** CREATOR 응모권을 사용하는 USER_BALANCE_SCOPE Evidence를 생성한다. */
    private DetectionEvidence userBalanceScopeEvidence() {
        return new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.USER_BALANCE_SCOPE,
                        10L, null, null, null, BalanceScope.creator(10L)),
                new DetectionEvidence.Window(10_000L, null),
                Map.of(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 5L),
                Map.of(AbuseMetric.INSUFFICIENT_BALANCE_FAILURE_COUNT, 3L),
                Set.of(),
                Set.of());
    }
}

package kr.co.cking.abuse.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionEvidence;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** DetectionEvidence와 MySQL JSON 컬럼 문자열을 상호 변환한다. */
@Component
class AbuseDetectionEvidenceJsonMapper {

    private final ObjectMapper objectMapper;

    /** Spring이 설정한 ObjectMapper를 받아 Evidence JSON 형식을 일관되게 처리한다. */
    AbuseDetectionEvidenceJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Domain Evidence를 MySQL JSON 컬럼에 저장할 문자열로 직렬화한다. */
    String toJson(DetectionEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(toPersistenceValue(evidence));
        } catch (JacksonException exception) {
            throw new IllegalStateException("DetectionEvidence를 JSON으로 직렬화할 수 없습니다.", exception);
        }
    }

    /** MySQL JSON 컬럼 문자열을 검증된 Domain Evidence로 역직렬화한다. */
    DetectionEvidence fromJson(String evidenceJson) {
        try {
            return toDomain(objectMapper.readValue(evidenceJson, EvidenceJson.class));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("DetectionEvidence JSON을 복원할 수 없습니다.", exception);
        }
    }

    /** 순수 Domain Evidence를 문서화된 Evidence JSON 값 객체로 변환한다. */
    private EvidenceJson toPersistenceValue(DetectionEvidence evidence) {
        DetectionEvidence.Scope scope = evidence.scope();
        DetectionEvidence.Window window = evidence.window();
        return new EvidenceJson(
                evidence.policyVersion(),
                new ScopeJson(
                        scope.type().name(),
                        scope.creatorId(),
                        scope.eventId(),
                        scope.missionId(),
                        scope.periodKey(),
                        toBalanceScopeJson(scope.balanceScope())),
                new WindowJson(window.windowMs(), window.maxDelayMs()),
                toPersistenceMetrics(evidence.features()),
                toPersistenceMetrics(evidence.thresholds()),
                evidence.signals().stream().map(AbuseSignal::name).collect(Collectors.toUnmodifiableSet()),
                evidence.matchedRules().stream()
                        .map(AbuseCompositeRule::evidenceValue)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    /** 문서화된 Evidence JSON 값 객체를 검증되는 순수 Domain Evidence로 복원한다. */
    private DetectionEvidence toDomain(EvidenceJson evidence) {
        ScopeJson scope = evidence.scope();
        WindowJson window = evidence.window();
        return new DetectionEvidence(
                evidence.policyVersion(),
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.valueOf(scope.type()),
                        scope.creatorId(),
                        scope.eventId(),
                        scope.missionId(),
                        scope.periodKey(),
                        toBalanceScope(scope.balanceScope())),
                new DetectionEvidence.Window(window.windowMs(), window.maxDelayMs()),
                toDomainMetrics(evidence.features()),
                toDomainMetrics(evidence.thresholds()),
                evidence.signals().stream().map(AbuseSignal::valueOf).collect(Collectors.toUnmodifiableSet()),
                evidence.matchedRules().stream()
                        .map(AbuseCompositeRule::fromEvidenceValue)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    /** Domain Metric map을 문서화된 lowerCamelCase JSON key map으로 변환한다. */
    private Map<String, Long> toPersistenceMetrics(Map<AbuseMetric, Long> metrics) {
        Map<String, Long> converted = new LinkedHashMap<>();
        metrics.forEach((metric, value) -> converted.put(metric.evidenceKey(), value));
        return Map.copyOf(converted);
    }

    /** 문서화된 lowerCamelCase JSON key map을 Domain Metric map으로 복원한다. */
    private Map<AbuseMetric, Long> toDomainMetrics(Map<String, Long> metrics) {
        Map<AbuseMetric, Long> converted = new LinkedHashMap<>();
        metrics.forEach((metric, value) -> converted.put(AbuseMetric.fromEvidenceKey(metric), value));
        return Map.copyOf(converted);
    }

    /** Domain BalanceScope를 JSON에 노출할 값 객체로 변환한다. */
    private BalanceScopeJson toBalanceScopeJson(BalanceScope balanceScope) {
        if (balanceScope == null) {
            return null;
        }
        return new BalanceScopeJson(balanceScope.type().name(), balanceScope.creatorId());
    }

    /** JSON BalanceScope 값을 Domain BalanceScope로 복원한다. */
    private BalanceScope toBalanceScope(BalanceScopeJson balanceScope) {
        if (balanceScope == null) {
            return null;
        }
        return new BalanceScope(BalanceScope.Type.valueOf(balanceScope.type()), balanceScope.creatorId());
    }

    /** MySQL JSON 및 관리자 API가 공유하는 Evidence 최상위 표현이다. */
    private record EvidenceJson(
            String policyVersion,
            ScopeJson scope,
            WindowJson window,
            Map<String, Long> features,
            Map<String, Long> thresholds,
            Set<String> signals,
            Set<String> matchedRules
    ) {
    }

    /** Evidence Scope의 구조화된 식별자를 JSON 필드로 유지한다. */
    private record ScopeJson(
            String type,
            Long creatorId,
            Long eventId,
            Long missionId,
            String periodKey,
            BalanceScopeJson balanceScope
    ) {
    }

    /** Evidence window의 밀리초 단위 측정값을 JSON 필드로 유지한다. */
    private record WindowJson(long windowMs, Long maxDelayMs) {
    }

    /** 공용 또는 Creator 전용 잔액 범위를 JSON 필드로 유지한다. */
    private record BalanceScopeJson(String type, Long creatorId) {
    }
}

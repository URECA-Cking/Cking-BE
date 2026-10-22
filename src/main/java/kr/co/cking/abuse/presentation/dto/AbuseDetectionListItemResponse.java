package kr.co.cking.abuse.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionEvidence;

/** 관리자 목록에서 민감한 전체 Evidence 대신 요약 근거만 반환하는 Detection 응답이다. */
public record AbuseDetectionListItemResponse(
        Long detectionId,
        Long memberId,
        AbuseType abuseType,
        AbuseDetectionStatus status,
        Instant detectedAt,
        Instant reviewedAt,
        Long reviewedBy,
        EvidenceSummary evidenceSummary
) {

    /** Domain Detection을 목록 API의 식별자 JSON number 응답으로 변환한다. */
    public static AbuseDetectionListItemResponse from(AbuseDetection detection) {
        return new AbuseDetectionListItemResponse(
                detection.detectionId(),
                detection.memberId(),
                detection.abuseType(),
                detection.status(),
                detection.detectedAt(),
                detection.reviewedAt(),
                detection.reviewedBy(),
                EvidenceSummary.from(detection.evidence()));
    }

    /** 전체 Evidence를 노출하지 않고 목록 탐색에 필요한 매칭 규칙·신호·범위만 담는다. */
    public record EvidenceSummary(
            Set<String> matchedRules,
            Set<AbuseSignal> signals,
            ScopeSummary scope
    ) {

        /** Domain Evidence에서 목록 응답에 허용된 요약 필드만 골라 변환한다. */
        static EvidenceSummary from(DetectionEvidence evidence) {
            return new EvidenceSummary(
                    evidence.matchedRules().stream()
                            .map(AbuseCompositeRule::evidenceValue)
                            .collect(Collectors.toUnmodifiableSet()),
                    evidence.signals(),
                    ScopeSummary.from(evidence.scope()));
        }
    }

    /** Evidence scope의 유형과 존재하는 업무 식별자만 목록에 구조화해 반환한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScopeSummary(
            DetectionEvidence.Scope.Type type,
            Long creatorId,
            Long eventId,
            Long missionId,
            String periodKey,
            BalanceScope balanceScope
    ) {

        /** Domain scope를 응답용 요약 scope로 변환한다. */
        static ScopeSummary from(DetectionEvidence.Scope scope) {
            return new ScopeSummary(
                    scope.type(),
                    scope.creatorId(),
                    scope.eventId(),
                    scope.missionId(),
                    scope.periodKey(),
                    scope.balanceScope());
        }
    }
}

package kr.co.cking.abuse.presentation.dto;

import java.time.Instant;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;

/** 관리자 상세 조회와 검토 결과에 전체 Evidence를 포함해 반환하는 Detection 응답이다. */
public record AbuseDetectionDetailResponse(
        Long detectionId,
        Long memberId,
        AbuseType abuseType,
        AbuseDetectionStatus status,
        Instant detectedAt,
        Instant reviewedAt,
        Long reviewedBy,
        DetectionEvidence evidence
) {

    /** Domain Detection을 전체 Evidence를 유지하는 상세 API 응답으로 변환한다. */
    public static AbuseDetectionDetailResponse from(AbuseDetection detection) {
        return new AbuseDetectionDetailResponse(
                detection.detectionId(),
                detection.memberId(),
                detection.abuseType(),
                detection.status(),
                detection.detectedAt(),
                detection.reviewedAt(),
                detection.reviewedBy(),
                detection.evidence());
    }
}

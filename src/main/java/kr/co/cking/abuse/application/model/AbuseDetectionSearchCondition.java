package kr.co.cking.abuse.application.model;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseType;

import java.time.Instant;

/** 관리자 Detection 목록의 선택 검색 조건이다. */
public record AbuseDetectionSearchCondition(
        Long memberId,
        AbuseType abuseType,
        AbuseDetectionStatus status,
        Instant detectedAtFrom,
        Instant detectedAtTo
) {
    public AbuseDetectionSearchCondition {
        if (memberId != null) {
            requirePositive(memberId, "memberId");
        }
        if (detectedAtFrom != null && detectedAtTo != null && detectedAtFrom.isAfter(detectedAtTo)) {
            throw new IllegalArgumentException("detectedAtFrom은 detectedAtTo보다 늦을 수 없습니다.");
        }
    }
}

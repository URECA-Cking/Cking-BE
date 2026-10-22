package kr.co.cking.abuse.presentation.dto;

import jakarta.validation.constraints.NotNull;
import kr.co.cking.abuse.domain.AbuseReviewDecision;

/** 관리자 검토에서 허용된 종결 판정만 전달하는 요청 본문이다. */
public record AbuseDetectionReviewRequest(@NotNull AbuseReviewDecision status) {
}

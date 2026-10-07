package kr.co.cking.interest.application.dto;

import java.math.BigDecimal;
import java.util.List;

/** Cking-LLM이 전달한 관심 분야별 추천 후보 묶음이다. 값 검증은 {@code InterestRecommendationBundleValidator}가 한다. */
public record InterestRecommendationCommand(
        Long applicationSequence,
        String taxonomyVersion,
        String taxonomyHash,
        String interestCode,
        String method,
        String modelVersion,
        String inputHash,
        List<Candidate> candidates
) {

    public record Candidate(
            String interestCode,
            Long creatorId,
            BigDecimal score,
            Integer rank,
            String method,
            String modelVersion,
            String inputHash
    ) {
    }
}

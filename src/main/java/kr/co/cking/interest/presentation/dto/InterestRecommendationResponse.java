package kr.co.cking.interest.presentation.dto;

import kr.co.cking.interest.application.InterestRecommendationResultService;

public final class InterestRecommendationResponse {

    private InterestRecommendationResponse() {
    }

    public record Stored(
            String taxonomyVersion,
            String interestCode,
            Long generationId,
            String inputHash,
            int candidateCount,
            boolean applied
    ) {
        public static Stored from(InterestRecommendationResultService.StoreResult result) {
            return new Stored(
                    result.taxonomyVersion(), result.interestCode(), result.generationId(),
                    result.inputHash(), result.candidateCount(), result.applied());
        }
    }
}

package kr.co.cking.interest.application.dto;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Cking-LLM이 전달한 관심 분야별 추천 후보 묶음이다. 값 검증은 {@code InterestRecommendationBundleValidator}가 한다. */
public record InterestRecommendationCommand(
        Long applicationSequence,
        String taxonomyVersion,
        String taxonomyHash,
        String interestCode,
        String method,
        String modelVersion,
        String inputHash,
        List<Candidate> candidates,
        JsonNode eligibility
) {

    /** 자격 근거 없이 적재하는 구 호출용이다. 근거 없는 세대는 조회에서 제외된다. */
    public InterestRecommendationCommand(
            Long applicationSequence, String taxonomyVersion, String taxonomyHash, String interestCode,
            String method, String modelVersion, String inputHash, List<Candidate> candidates) {
        this(applicationSequence, taxonomyVersion, taxonomyHash, interestCode, method, modelVersion, inputHash,
                candidates, null);
    }

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

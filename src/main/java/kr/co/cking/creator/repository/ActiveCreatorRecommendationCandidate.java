package kr.co.cking.creator.repository;

import java.math.BigDecimal;

/** 팔로우 seed의 현재 활성 유사 추천 세대에서 일괄 조회한 후보 행이다. */
public record ActiveCreatorRecommendationCandidate(
        Long seedCreatorId,
        Long candidateCreatorId,
        String method,
        BigDecimal rawScore,
        int rank,
        Long generationId,
        String modelVersion
) {
}

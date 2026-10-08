package kr.co.cking.creator.application.dto;

import kr.co.cking.creator.domain.RecommendationSourceType;

/** 추천을 계산할 때 읽은 세대 provenance. 원문 소개나 인증 정보는 포함하지 않는다. */
public record RecommendationSource(RecommendationSourceType sourceType, String sourceKey, String taxonomyVersion,
                                   Long generationId, String method, String modelVersion, int sourceRank) {
}

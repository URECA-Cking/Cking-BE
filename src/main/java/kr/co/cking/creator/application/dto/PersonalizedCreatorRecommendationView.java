package kr.co.cking.creator.application.dto;

import java.math.BigDecimal;
import java.util.List;

/** 인증된 사용자의 팔로우 seed를 합쳐 만든 개인화 Creator 추천 결과다. */
public record PersonalizedCreatorRecommendationView(
        String policyVersion,
        List<Item> items
) {
    public record Item(
            Long creatorId,
            String creatorName,
            String introText,
            String profileImageUrl,
            BigDecimal aggregateScore,
            List<Long> seedCreatorIds
    ) {
    }
}

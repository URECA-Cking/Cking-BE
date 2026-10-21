package kr.co.cking.creator.application.dto;

import java.math.BigDecimal;
import java.util.List;

/** 인증된 사용자의 관심 분야·팔로우 seed 후보를 합쳐 만든 개인화 Creator 추천 결과다. */
public record PersonalizedCreatorRecommendationView(
        String policyVersion,
        List<Item> items
) {
    /** followerCount는 추천 카드에 보여줄 팔로워 수다. 점수·정렬에는 쓰이지 않는다. */
    public record Item(
            Long creatorId,
            String creatorName,
            String introText,
            String profileImageUrl,
            BigDecimal aggregateScore,
            List<String> interestCodes,
            List<Long> seedCreatorIds,
            List<RecommendationSource> sources,
            long followerCount
    ) {
        /** 추천 근거 출처와 팔로워 수 없이 만든다(출처는 빈 목록, 팔로워 수는 0). 값이 필요 없는 호출자용이다. */
        public Item(Long creatorId, String creatorName, String introText, String profileImageUrl,
                    BigDecimal aggregateScore, List<String> interestCodes, List<Long> seedCreatorIds) {
            this(creatorId, creatorName, introText, profileImageUrl, aggregateScore, interestCodes, seedCreatorIds, List.of(), 0L);
        }

        /** 추천 근거 출처는 있고 팔로워 수는 없이 만든다(팔로워 수 0). 값이 필요 없는 호출자용이다. */
        public Item(Long creatorId, String creatorName, String introText, String profileImageUrl,
                    BigDecimal aggregateScore, List<String> interestCodes, List<Long> seedCreatorIds,
                    List<RecommendationSource> sources) {
            this(creatorId, creatorName, introText, profileImageUrl, aggregateScore, interestCodes, seedCreatorIds, sources, 0L);
        }
    }
}

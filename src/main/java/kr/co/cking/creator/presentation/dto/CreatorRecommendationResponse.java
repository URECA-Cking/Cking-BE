package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;

import java.math.BigDecimal;
import java.util.List;

public final class CreatorRecommendationResponse {

    private CreatorRecommendationResponse() {
    }

    public record Result(
            String policyVersion,
            List<Item> items
    ) {
        public static Result from(PersonalizedCreatorRecommendationView view) {
            return new Result(
                    view.policyVersion(),
                    view.items().stream().map(Item::from).toList());
        }
    }

    public record Item(
            Long creatorId,
            String creatorName,
            String introText,
            String profileImageUrl,
            BigDecimal aggregateScore,
            List<String> interestCodes,
            List<Long> seedCreatorIds
    ) {
        private static Item from(PersonalizedCreatorRecommendationView.Item item) {
            return new Item(
                    item.creatorId(),
                    item.creatorName(),
                    item.introText(),
                    item.profileImageUrl(),
                    item.aggregateScore(),
                    item.interestCodes(),
                    item.seedCreatorIds());
        }
    }
}

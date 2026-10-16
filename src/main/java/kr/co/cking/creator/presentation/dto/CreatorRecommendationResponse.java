package kr.co.cking.creator.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class CreatorRecommendationResponse {

    private CreatorRecommendationResponse() {
    }

    public record Result(
            UUID recommendationRequestId,
            String policyVersion,
            List<Item> items
    ) {
        /** 추천 View와 추천 요청 ID로 응답을 만든다. 스냅샷 기록에 실패해 ID가 없으면 null이 담긴다. */
        public static Result from(PersonalizedCreatorRecommendationView view, UUID requestId) {
            return new Result(
                    requestId,
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
            List<Long> seedCreatorIds,
            @Schema(description = "이 Creator를 팔로우한 회원 수(0 이상). 요청 시점에 센 값이며 추천 점수·정렬에는 쓰이지 않습니다.",
                    example = "1280")
            long followerCount
    ) {
        /** 추천 카드 View 한 건을 응답 항목으로 옮긴다. */
        private static Item from(PersonalizedCreatorRecommendationView.Item item) {
            return new Item(
                    item.creatorId(),
                    item.creatorName(),
                    item.introText(),
                    item.profileImageUrl(),
                    item.aggregateScore(),
                    item.interestCodes(),
                    item.seedCreatorIds(),
                    item.followerCount());
        }
    }
}

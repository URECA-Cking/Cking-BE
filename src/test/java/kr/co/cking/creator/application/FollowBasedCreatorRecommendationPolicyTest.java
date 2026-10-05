package kr.co.cking.creator.application;

import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FollowBasedCreatorRecommendationPolicyTest {

    private final FollowBasedCreatorRecommendationPolicy policy =
            new FollowBasedCreatorRecommendationPolicy();

    @Test
    void M2와_M4의_raw_score를_섞지_않고_RRF_rank로_중복_후보를_합친다() {
        List<FollowBasedCreatorRecommendationPolicy.RankedCandidate> result = policy.aggregate(List.of(
                row(1L, 20L, "M2", "1.90000000", 2),
                row(2L, 20L, "M4", "-0.90000000", 1),
                row(1L, 30L, "M2", "-0.50000000", 1),
                row(2L, 40L, "M4", "1.80000000", 1)
        ), Set.of(), 10);

        assertThat(result).containsExactly(
                new FollowBasedCreatorRecommendationPolicy.RankedCandidate(
                        20L, new BigDecimal("0.03252247"), List.of(1L, 2L)),
                new FollowBasedCreatorRecommendationPolicy.RankedCandidate(
                        30L, new BigDecimal("0.01639344"), List.of(1L)),
                new FollowBasedCreatorRecommendationPolicy.RankedCandidate(
                        40L, new BigDecimal("0.01639344"), List.of(2L))
        );
    }

    @Test
    void 팔로우와_본인_크리에이터를_제외하고_size만큼만_반환한다() {
        List<FollowBasedCreatorRecommendationPolicy.RankedCandidate> result = policy.aggregate(List.of(
                row(1L, 1L, "M2", "0.9", 1),
                row(1L, 2L, "M2", "0.8", 2),
                row(1L, 3L, "M2", "0.7", 3),
                row(1L, 4L, "M2", "0.6", 4)
        ), Set.of(1L, 2L), 1);

        assertThat(result).extracting(FollowBasedCreatorRecommendationPolicy.RankedCandidate::creatorId)
                .containsExactly(3L);
    }

    @Test
    void 점수가_같으면_creatorId_오름차순으로_결정적으로_정렬한다() {
        List<FollowBasedCreatorRecommendationPolicy.RankedCandidate> result = policy.aggregate(List.of(
                row(2L, 30L, "M4", "0.1", 5),
                row(1L, 20L, "M2", "1.9", 5)
        ), Set.of(), 10);

        assertThat(result).extracting(FollowBasedCreatorRecommendationPolicy.RankedCandidate::creatorId)
                .containsExactly(20L, 30L);
    }

    private ActiveCreatorRecommendationCandidate row(
            Long seedCreatorId,
            Long candidateCreatorId,
            String method,
            String rawScore,
            int rank
    ) {
        return new ActiveCreatorRecommendationCandidate(
                seedCreatorId, candidateCreatorId, method, new BigDecimal(rawScore), rank);
    }
}

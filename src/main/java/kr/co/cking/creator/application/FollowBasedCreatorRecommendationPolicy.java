package kr.co.cking.creator.application;

import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** M2/M4 raw score 대신 rank만 사용해 여러 팔로우 seed의 후보를 비교 가능한 점수로 합친다. */
@Component
public class FollowBasedCreatorRecommendationPolicy {

    public static final String VERSION = "FOLLOW_PERSONALIZED_V1";
    private static final int RRF_K = 60;
    private static final int SCORE_SCALE = 8;

    public List<RankedCandidate> aggregate(
            List<ActiveCreatorRecommendationCandidate> rows,
            Set<Long> excludedCreatorIds,
            int size
    ) {
        Map<Long, Accumulator> accumulators = new HashMap<>();
        for (ActiveCreatorRecommendationCandidate row : rows) {
            if (excludedCreatorIds.contains(row.candidateCreatorId())) {
                continue;
            }
            Accumulator accumulator = accumulators.computeIfAbsent(
                    row.candidateCreatorId(), ignored -> new Accumulator());
            accumulator.score = accumulator.score.add(rankContribution(row.rank()));
            accumulator.seedCreatorIds.add(row.seedCreatorId());
        }

        return accumulators.entrySet().stream()
                .map(entry -> new RankedCandidate(
                        entry.getKey(),
                        entry.getValue().score,
                        new ArrayList<>(entry.getValue().seedCreatorIds)))
                .sorted(Comparator.comparing(RankedCandidate::aggregateScore).reversed()
                        .thenComparing(RankedCandidate::creatorId))
                .limit(size)
                .toList();
    }

    private BigDecimal rankContribution(int rank) {
        return BigDecimal.ONE.divide(
                BigDecimal.valueOf(RRF_K + rank), SCORE_SCALE, RoundingMode.HALF_UP);
    }

    public record RankedCandidate(
            Long creatorId,
            BigDecimal aggregateScore,
            List<Long> seedCreatorIds
    ) {
        public RankedCandidate {
            seedCreatorIds = List.copyOf(seedCreatorIds);
        }
    }

    private static final class Accumulator {
        private BigDecimal score = BigDecimal.ZERO.setScale(SCORE_SCALE);
        private final Set<Long> seedCreatorIds = new TreeSet<>();
    }
}

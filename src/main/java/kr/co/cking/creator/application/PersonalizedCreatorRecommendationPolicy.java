package kr.co.cking.creator.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import kr.co.cking.creator.repository.ActiveCreatorRecommendationCandidate;
import kr.co.cking.interest.repository.ActiveInterestRecommendationCandidate;
import org.springframework.stereotype.Component;

/**
 * 관심 분야 후보와 팔로우 seed 후보를 rank만으로 합쳐 개인화 점수를 만든다. 정본은 Cking-LLM-Benchmark의
 * {@code hybrid-personalized-contract.md}와 공용 fixture이며 이 구현은 그 fixture 전체와 일치해야 한다.
 *
 * <ol>
 *   <li>본인·이미 팔로우한 Creator 등 제외 대상을 뺀 뒤 후보가 남는 source(분야·seed)만 <b>유효</b>하다. 무효 source는 분모에서 빠진다.
 *   <li>source별 기여도 {@code round8(1 / (60 + rank))}를 그룹 안에서 후보별로 합산하고, 그룹의 유효 source 수로 나눠
 *       {@code round8}한 평균을 그룹 점수로 쓴다.
 *   <li>두 그룹이 유효하면 {@code round8(0.5 × 관심 평균 + 0.5 × 팔로우 평균)}(없는 쪽은 0), 한 그룹만 유효하면 그 평균 그대로다.
 *   <li>점수 내림차순, 동점은 {@code creatorId} 오름차순이다.
 * </ol>
 *
 * 반올림은 기여도·그룹 평균·최종 합 세 번 모두 소수점 8자리 HALF_UP이며, 끝에 한 번만 반올림하면 결과가 달라진다.
 * M2/M3/M4 raw score는 입력이 아니다.
 */
@Component
public class PersonalizedCreatorRecommendationPolicy {

    public static final String HYBRID_VERSION = "HYBRID_PERSONALIZED_V1";
    public static final String INTEREST_VERSION = "INTEREST_PERSONALIZED_V1";
    /** 팔로우만 유효하거나 두 그룹 모두 무효일 때의 정책이다. 기존 V1(seed 합산)과 달리 유효 seed 평균을 쓴다. */
    public static final String FOLLOW_VERSION = "FOLLOW_PERSONALIZED_V2";

    private static final long RRF_K = 60L;
    private static final int SCALE = 8;
    private static final BigDecimal INTEREST_WEIGHT = new BigDecimal("0.5");
    private static final BigDecimal FOLLOW_WEIGHT = new BigDecimal("0.5");

    /** 제외 대상을 뺀 전체 순위다. 호출자가 {@code size}를 잘라 쓴다. */
    public Recommendation recommend(
            List<ActiveCreatorRecommendationCandidate> followRows,
            List<ActiveInterestRecommendationCandidate> interestRows,
            Set<Long> excludedCreatorIds
    ) {
        Group<Long> follow = group(followRows, ActiveCreatorRecommendationCandidate::seedCreatorId,
                ActiveCreatorRecommendationCandidate::candidateCreatorId,
                ActiveCreatorRecommendationCandidate::rank, excludedCreatorIds);
        Group<String> interest = group(interestRows, ActiveInterestRecommendationCandidate::interestCode,
                ActiveInterestRecommendationCandidate::creatorId,
                ActiveInterestRecommendationCandidate::rank, excludedCreatorIds);

        boolean interestValid = interest.validSources() > 0;
        boolean followValid = follow.validSources() > 0;
        String version = interestValid && followValid ? HYBRID_VERSION
                : interestValid ? INTEREST_VERSION : FOLLOW_VERSION;

        Set<Long> candidateIds = new HashSet<>();
        candidateIds.addAll(interest.byCandidate().keySet());
        candidateIds.addAll(follow.byCandidate().keySet());

        List<Item> items = new ArrayList<>();
        for (Long creatorId : candidateIds) {
            Scored<String> interestScore = interest.byCandidate().get(creatorId);
            Scored<Long> followScore = follow.byCandidate().get(creatorId);
            items.add(new Item(
                    creatorId,
                    score(interestValid, followValid, interestScore, followScore),
                    interestScore == null ? List.of() : List.copyOf(interestScore.sources()),
                    followScore == null ? List.of() : List.copyOf(followScore.sources())));
        }
        items.sort(Comparator.comparing(Item::aggregateScore).reversed().thenComparing(Item::creatorId));
        return new Recommendation(version, interest.validSources(), follow.validSources(), List.copyOf(items));
    }

    private BigDecimal score(
            boolean interestValid,
            boolean followValid,
            Scored<String> interest,
            Scored<Long> follow
    ) {
        if (interestValid && followValid) {
            return mean(interest).multiply(INTEREST_WEIGHT)
                    .add(mean(follow).multiply(FOLLOW_WEIGHT))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        return interestValid ? mean(interest) : mean(follow);
    }

    /** 후보가 나오지 않은 그룹의 평균은 0이다. */
    private BigDecimal mean(Scored<?> scored) {
        return scored == null ? BigDecimal.ZERO.setScale(SCALE) : scored.mean();
    }

    private <K extends Comparable<K>, R> Group<K> group(
            List<R> rows,
            Function<R, K> source,
            ToLongFunction<R> candidate,
            ToIntFunction<R> rank,
            Set<Long> excludedCreatorIds
    ) {
        Map<Long, Accumulator<K>> accumulators = new HashMap<>();
        Set<K> validSources = new HashSet<>();
        for (R row : rows) {
            long candidateId = candidate.applyAsLong(row);
            if (excludedCreatorIds.contains(candidateId)) {
                continue;
            }
            K sourceKey = source.apply(row);
            validSources.add(sourceKey);
            Accumulator<K> accumulator = accumulators.computeIfAbsent(candidateId, ignored -> new Accumulator<>());
            accumulator.sum = accumulator.sum.add(contribution(rank.applyAsInt(row)));
            accumulator.sources.add(sourceKey);
        }

        Map<Long, Scored<K>> byCandidate = new HashMap<>();
        BigDecimal divisor = BigDecimal.valueOf(validSources.size());
        accumulators.forEach((candidateId, accumulator) -> byCandidate.put(candidateId, new Scored<>(
                accumulator.sum.divide(divisor, SCALE, RoundingMode.HALF_UP), accumulator.sources)));
        return new Group<>(validSources.size(), byCandidate);
    }

    /** rank는 양의 int 전체 범위일 수 있어 long으로 더한다. */
    private BigDecimal contribution(int rank) {
        return BigDecimal.ONE.divide(BigDecimal.valueOf(RRF_K + rank), SCALE, RoundingMode.HALF_UP);
    }

    public record Recommendation(String policyVersion, int validInterestSources, int validFollowSources, List<Item> items) {
    }

    /**
     * 추천 후보 하나다. {@code interestCodes}는 기여한 분야(ASCII 사전순), {@code seedCreatorIds}는 기여한 seed(숫자 오름차순)이며
     * 해당 그룹에서 기여하지 않았으면 비어 있다.
     */
    public record Item(Long creatorId, BigDecimal aggregateScore, List<String> interestCodes, List<Long> seedCreatorIds) {
    }

    private record Group<K>(int validSources, Map<Long, Scored<K>> byCandidate) {
    }

    private record Scored<K>(BigDecimal mean, Set<K> sources) {
    }

    private static final class Accumulator<K extends Comparable<K>> {
        private BigDecimal sum = BigDecimal.ZERO.setScale(SCALE);
        private final Set<K> sources = new TreeSet<>();
    }
}

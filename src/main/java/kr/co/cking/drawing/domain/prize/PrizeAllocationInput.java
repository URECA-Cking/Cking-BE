package kr.co.cking.drawing.domain.prize;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.PrizeValue;

/** 당첨자 선정 결과와 분리된 상품 배정 엔진 입력이다. */
public record PrizeAllocationInput(
        DrawingSeed seed,
        String algorithmVersion,
        List<DrawWinner> winners,
        List<PrizeValue> prizes
) {
    public PrizeAllocationInput {
        if (seed == null || algorithmVersion == null || algorithmVersion.isBlank()
                || winners == null || prizes == null) {
            throw new IllegalArgumentException("상품 배정 입력은 모두 필수입니다.");
        }
        winners = winners.stream().sorted(Comparator.comparingInt(DrawWinner::rank)).toList();
        prizes = prizes.stream().sorted(Comparator.comparingInt(PrizeValue::priority)
                .thenComparing(PrizeValue::prizeKey)).toList();
        HashSet<String> keys = new HashSet<>();
        long quantity = 0;
        PrizeAllocationAlgorithmVersion version = PrizeAllocationAlgorithmVersion.from(algorithmVersion);
        long weight = 0;
        for (PrizeValue prize : prizes) {
            if (!keys.add(prize.prizeKey())) {
                throw new IllegalArgumentException("상품 식별자는 중복될 수 없습니다.");
            }
            quantity = Math.addExact(quantity, prize.quantity());
            if (version == PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1) {
                weight = Math.addExact(weight, prize.weight());
            }
        }
        if (quantity < winners.size()) {
            throw new IllegalArgumentException("총 상품 수량이 당첨자 수보다 작습니다.");
        }
    }
}

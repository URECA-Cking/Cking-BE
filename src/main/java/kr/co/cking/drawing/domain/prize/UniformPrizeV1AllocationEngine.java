package kr.co.cking.drawing.domain.prize;

import java.util.ArrayList;
import java.util.List;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.snapshot.domain.PrizeValue;

/** 재고가 남은 각 상품 등급을 동일한 확률로 선택하는 PRIZE_UNIFORM_V1 구현체다. */
public final class UniformPrizeV1AllocationEngine implements PrizeAllocationEngine {

    @Override
    public PrizeAllocationOutput allocate(PrizeAllocationInput input) {
        if (input == null || PrizeAllocationAlgorithmVersion.from(input.algorithmVersion())
                != PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1) {
            throw new IllegalArgumentException("PRIZE_UNIFORM_V1 입력이 필요합니다.");
        }

        int[] remaining = input.prizes().stream().mapToInt(PrizeValue::quantity).toArray();
        DeterministicRandom random = new DeterministicRandom(PrizeAllocationSeed.derive(input.seed()));
        List<AllocatedPrize> allocations = new ArrayList<>(input.winners().size());

        for (var winner : input.winners()) {
            int availablePrizeTypeCount = 0;
            for (int quantity : remaining) {
                if (quantity > 0) {
                    availablePrizeTypeCount++;
                }
            }

            long selected = random.nextLong(availablePrizeTypeCount);
            PrizeValue prize = select(input.prizes(), remaining, selected);
            allocations.add(new AllocatedPrize(winner.memberId(), winner.rank(), prize));
        }

        return new PrizeAllocationOutput(PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1, allocations);
    }

    private PrizeValue select(List<PrizeValue> prizes, int[] remaining, long selected) {
        long current = 0;
        for (int index = 0; index < prizes.size(); index++) {
            if (remaining[index] == 0) {
                continue;
            }
            if (current == selected) {
                remaining[index]--;
                return prizes.get(index);
            }
            current++;
        }
        throw new IllegalStateException("균등 상품 구간에서 배정 대상을 찾지 못했습니다.");
    }
}

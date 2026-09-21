package kr.co.cking.drawing.domain.prize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.PrizeValue;

/** 남은 재고가 있는 상품만 누적 정수 가중치 구간에 포함하는 PRIZE_WEIGHTED_V1 구현체다. */
public final class WeightedPrizeV1AllocationEngine implements PrizeAllocationEngine {
    private static final byte[] DOMAIN = "CKING_PRIZE_ALLOCATION_V1".getBytes(StandardCharsets.UTF_8);

    @Override
    public PrizeAllocationOutput allocate(PrizeAllocationInput input) {
        if (input == null || PrizeAllocationAlgorithmVersion.from(input.algorithmVersion())
                != PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1) {
            throw new IllegalArgumentException("PRIZE_WEIGHTED_V1 입력이 필요합니다.");
        }
        int[] remaining = input.prizes().stream().mapToInt(PrizeValue::quantity).toArray();
        DeterministicRandom random = new DeterministicRandom(derivePrizeSeed(input.seed()));
        List<AllocatedPrize> allocations = new ArrayList<>(input.winners().size());
        for (var winner : input.winners()) {
            long totalWeight = 0;
            for (int index = 0; index < input.prizes().size(); index++) {
                if (remaining[index] > 0) {
                    totalWeight = Math.addExact(totalWeight, input.prizes().get(index).weight());
                }
            }
            long selected = random.nextLong(totalWeight);
            PrizeValue prize = select(input.prizes(), remaining, selected);
            allocations.add(new AllocatedPrize(winner.memberId(), winner.rank(), prize));
        }
        return new PrizeAllocationOutput(PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, allocations);
    }

    private PrizeValue select(List<PrizeValue> prizes, int[] remaining, long selected) {
        long cumulative = 0;
        for (int index = 0; index < prizes.size(); index++) {
            if (remaining[index] == 0) {
                continue;
            }
            cumulative = Math.addExact(cumulative, prizes.get(index).weight());
            if (selected < cumulative) {
                remaining[index]--;
                return prizes.get(index);
            }
        }
        throw new IllegalStateException("상품 가중치 구간에서 배정 대상을 찾지 못했습니다.");
    }

    private DrawingSeed derivePrizeSeed(DrawingSeed seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed.bytes());
            digest.update(DOMAIN);
            return DrawingSeed.fromBytes(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}

package kr.co.cking.drawing.domain.prize;

import java.util.HashSet;
import java.util.List;

public record PrizeAllocationOutput(PrizeAllocationAlgorithmVersion algorithmVersion,
                                    List<AllocatedPrize> allocations) {
    public PrizeAllocationOutput {
        if (algorithmVersion == null || allocations == null) {
            throw new IllegalArgumentException("상품 배정 결과는 필수입니다.");
        }
        HashSet<Long> members = new HashSet<>();
        HashSet<Integer> ranks = new HashSet<>();
        for (AllocatedPrize allocation : allocations) {
            if (allocation == null || !members.add(allocation.memberId()) || !ranks.add(allocation.rank())) {
                throw new IllegalArgumentException("상품 배정의 당첨자와 rank는 중복될 수 없습니다.");
            }
        }
        allocations = List.copyOf(allocations);
    }
}

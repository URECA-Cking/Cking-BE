package kr.co.cking.drawing.domain.prize;

public interface PrizeAllocationEngine {
    PrizeAllocationOutput allocate(PrizeAllocationInput input);
}

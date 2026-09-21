package kr.co.cking.drawing.domain.prize;

import kr.co.cking.snapshot.domain.PrizeValue;

/** 한 당첨자에게 확정된 상품 등급 배정 결과다. */
public record AllocatedPrize(Long memberId, int rank, PrizeValue prize) {
    public AllocatedPrize {
        if (memberId == null || memberId <= 0 || rank <= 0 || prize == null) {
            throw new IllegalArgumentException("상품 배정 결과가 올바르지 않습니다.");
        }
    }
}

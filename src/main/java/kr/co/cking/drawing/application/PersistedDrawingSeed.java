package kr.co.cking.drawing.application;

import kr.co.cking.drawing.domain.seed.DrawingSeed;

/** Drawing 저장용 식별자와 엔진 입력용 Seed를 함께 전달하는 내부 계약. */
public record PersistedDrawingSeed(Long seedId, DrawingSeed seed) {

    public PersistedDrawingSeed {
        if (seedId == null || seedId <= 0) {
            throw new IllegalArgumentException("seedId는 양수여야 합니다.");
        }
        if (seed == null) {
            throw new IllegalArgumentException("DrawingSeed는 필수입니다.");
        }
    }
}

package kr.co.cking.drawing.domain;

/** 원본 Seed 재현에서 선정된 당첨자의 순위와 Snapshot 상품 정보다. */
public record DrawingReplayedWinner(
        int rankInDrawing,
        Long userId,
        String prizeKey,
        String prizeDisplayName,
        Integer prizePriority
) {
}

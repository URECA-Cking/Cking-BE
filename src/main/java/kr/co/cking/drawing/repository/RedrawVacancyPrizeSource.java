package kr.co.cking.drawing.repository;

/** REDRAW가 고정 결원 Winner에게서 승계할 상품 Snapshot 식별자다. */
public record RedrawVacancyPrizeSource(Long winnerId, Long snapshotPrizeId) {
}

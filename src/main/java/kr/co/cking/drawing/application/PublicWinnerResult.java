package kr.co.cking.drawing.application;

import kr.co.cking.winner.domain.Winner;

public record PublicWinnerResult(Long winnerId, Long userId, int rankInDrawing,
                                 String prizeKey, String prizeDisplayName, Integer prizePriority) {
    public static PublicWinnerResult from(Winner winner) {
        return new PublicWinnerResult(winner.getId(), winner.getMemberId(), winner.getRankInDrawing(),
                winner.getPrizeKey(), winner.getPrizeDisplayName(), winner.getPrizePriority());
    }
}

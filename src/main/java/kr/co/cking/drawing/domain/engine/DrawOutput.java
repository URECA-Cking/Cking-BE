package kr.co.cking.drawing.domain.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DrawOutput(
        DrawingAlgorithmVersion algorithmVersion,
        List<DrawWinner> winners
) {

    public DrawOutput {
        if (algorithmVersion == null) {
            throw new IllegalArgumentException("algorithmVersion은 필수입니다.");
        }
        if (winners == null) {
            throw new IllegalArgumentException("winners는 필수입니다.");
        }
        validateWinners(winners);
        winners = List.copyOf(winners);
    }

    private static void validateWinners(List<DrawWinner> winners) {
        Set<Long> memberIds = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();

        for (DrawWinner winner : winners) {
            if (winner == null) {
                throw new IllegalArgumentException("winner는 null일 수 없습니다.");
            }
            if (!memberIds.add(winner.memberId())) {
                throw new IllegalArgumentException("당첨자의 memberId는 중복될 수 없습니다.");
            }
            if (!ranks.add(winner.rank())) {
                throw new IllegalArgumentException("당첨자의 rank는 중복될 수 없습니다.");
            }
        }
    }
}

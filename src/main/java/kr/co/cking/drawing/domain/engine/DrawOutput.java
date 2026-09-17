package kr.co.cking.drawing.domain.engine;

import java.util.List;

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
        winners = List.copyOf(winners);
    }
}

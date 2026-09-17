package kr.co.cking.drawing.domain.engine;

import java.util.ArrayList;
import java.util.List;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.snapshot.domain.CandidateValue;

/**
 * 응모권 수에 비례하여 복원 없이 당첨자를 선정하는 WEIGHTED_V1 구현체.
 */
public final class WeightedV1DrawingEngine implements DrawingEngine {

    @Override
    public DrawOutput draw(DrawInput input) {
        if (input == null) {
            throw new IllegalArgumentException("DrawInput은 필수입니다.");
        }

        DrawingAlgorithmVersion algorithmVersion =
                DrawingAlgorithmVersion.from(input.algorithmVersion());
        if (algorithmVersion != DrawingAlgorithmVersion.WEIGHTED_V1) {
            throw new IllegalArgumentException(
                    "WeightedV1DrawingEngine은 WEIGHTED_V1만 지원합니다."
            );
        }

        List<CandidateValue> eligibleCandidates = input.candidates().stream()
                .filter(candidate -> !input.excludedMemberIds().contains(candidate.memberId()))
                .toList();

        if (input.winnerCount() > eligibleCandidates.size()) {
            throw new IllegalArgumentException(
                    "제외 대상을 반영한 후보 수보다 winnerCount가 클 수 없습니다."
            );
        }

        WeightedCandidatePool drawingPool = new WeightedCandidatePool(eligibleCandidates);
        DeterministicRandom random = new DeterministicRandom(input.seed());
        List<DrawWinner> winners = new ArrayList<>(input.winnerCount());

        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            long selectedWeight = random.nextLong(drawingPool.totalWeight());
            CandidateValue winner = drawingPool.selectAndRemove(selectedWeight);

            // 가중치를 0으로 갱신하여 동일 사용자의 중복 당첨 방지.
            winners.add(new DrawWinner(winner.memberId(), rank, winner.ticketCount()));
        }

        return new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, winners);
    }
}

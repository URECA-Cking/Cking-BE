package kr.co.cking.drawing.domain.engine;

import java.util.ArrayList;
import java.util.List;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.snapshot.domain.CandidateValue;

/**
 * 외부 저장소에 의존하지 않는 결정적 추첨 엔진.
 */
public final class DrawingEngine {

    public DrawOutput draw(DrawInput input) {
        if (input == null) {
            throw new IllegalArgumentException("DrawInput은 필수입니다.");
        }

        DrawingAlgorithmVersion algorithmVersion =
                DrawingAlgorithmVersion.from(input.algorithmVersion());
        return switch (algorithmVersion) {
            case WEIGHTED_V1 -> drawWeightedV1(input);
        };
    }

    private DrawOutput drawWeightedV1(DrawInput input) {
        List<CandidateValue> drawingPool = input.candidates().stream()
                .filter(candidate -> !input.excludedMemberIds().contains(candidate.memberId()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        if (input.winnerCount() > drawingPool.size()) {
            throw new IllegalArgumentException(
                    "제외 대상을 반영한 후보 수보다 winnerCount가 클 수 없습니다."
            );
        }

        DeterministicRandom random = new DeterministicRandom(input.seed());
        List<DrawWinner> winners = new ArrayList<>(input.winnerCount());

        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            int winnerIndex = selectWinnerIndex(drawingPool, random);
            CandidateValue winner = drawingPool.remove(winnerIndex);

            // 동일 사용자의 중복 당첨 방지를 위한 선정 후보의 추첨 대상 즉시 제거.
            winners.add(new DrawWinner(winner.memberId(), rank, winner.ticketCount()));
        }

        return new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, winners);
    }

    private int selectWinnerIndex(
            List<CandidateValue> drawingPool,
            DeterministicRandom random
    ) {
        long totalWeight = calculateTotalWeight(drawingPool);
        long selectedWeight = random.nextLong(totalWeight);
        long cumulativeWeight = 0L;

        // 후보 복제 없이 응모권 비율을 반영하기 위한 누적 가중치 구간 탐색.
        for (int index = 0; index < drawingPool.size(); index++) {
            cumulativeWeight = Math.addExact(
                    cumulativeWeight,
                    drawingPool.get(index).ticketCount()
            );
            if (selectedWeight < cumulativeWeight) {
                return index;
            }
        }

        throw new IllegalStateException("가중 추첨 구간을 결정할 수 없습니다.");
    }

    private long calculateTotalWeight(List<CandidateValue> drawingPool) {
        long totalWeight = 0L;
        try {
            for (CandidateValue candidate : drawingPool) {
                totalWeight = Math.addExact(totalWeight, candidate.ticketCount());
            }
            return totalWeight;
        } catch (ArithmeticException exception) {
            // 잘못된 난수 범위 생성을 방지하기 위한 long 가중치 합계 오버플로 차단.
            throw new IllegalArgumentException("Candidate 가중치 합계가 long 범위를 초과했습니다.", exception);
        }
    }
}

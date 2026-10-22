package kr.co.cking.drawing.domain.engine;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;

/**
 * 가중치 합 조회와 당첨 후보 제거를 O(log N)에 처리하는 Fenwick Tree 기반 후보군.
 */
final class WeightedCandidatePool {

    private final List<CandidateValue> candidates;
    private final long[] weightTree;
    private long totalWeight;

    WeightedCandidatePool(List<CandidateValue> candidates) {
        this.candidates = List.copyOf(candidates);
        this.weightTree = new long[candidates.size() + 1];
        buildWeightTree();
    }

    long totalWeight() {
        return totalWeight;
    }

    CandidateValue selectAndRemove(long selectedWeight) {
        if (selectedWeight < 0 || selectedWeight >= totalWeight) {
            throw new IllegalArgumentException("selectedWeight는 현재 가중치 합계 범위 안이어야 합니다.");
        }

        int candidateIndex = findCandidateIndex(selectedWeight);
        CandidateValue winner = candidates.get(candidateIndex);
        removeWeight(candidateIndex + 1, winner.ticketCount());
        totalWeight = Math.subtractExact(totalWeight, winner.ticketCount());
        return winner;
    }

    private void buildWeightTree() {
        try {
            for (int treeIndex = 1; treeIndex < weightTree.length; treeIndex++) {
                long weight = candidates.get(treeIndex - 1).ticketCount();
                totalWeight = Math.addExact(totalWeight, weight);
                weightTree[treeIndex] = Math.addExact(weightTree[treeIndex], weight);

                int parentIndex = treeIndex + Integer.lowestOneBit(treeIndex);
                if (parentIndex < weightTree.length) {
                    weightTree[parentIndex] = Math.addExact(
                            weightTree[parentIndex],
                            weightTree[treeIndex]
                    );
                }
            }
        } catch (ArithmeticException exception) {
            // 잘못된 난수 범위 생성을 방지하기 위한 long 가중치 합계 오버플로 차단.
            throw new IllegalArgumentException(
                    "Candidate 가중치 합계가 long 범위를 초과했습니다.",
                    exception
            );
        }
    }

    private int findCandidateIndex(long selectedWeight) {
        int treeIndex = 0;
        long prefixWeight = 0L;

        // selectedWeight 이하인 최대 누적합 위치 탐색 후 다음 후보 구간 선택.
        for (int step = Integer.highestOneBit(candidates.size()); step != 0; step >>= 1) {
            int nextIndex = treeIndex + step;
            if (nextIndex >= weightTree.length) {
                continue;
            }

            long nextPrefixWeight = prefixWeight + weightTree[nextIndex];
            if (nextPrefixWeight <= selectedWeight) {
                treeIndex = nextIndex;
                prefixWeight = nextPrefixWeight;
            }
        }

        return treeIndex;
    }

    private void removeWeight(int treeIndex, long weight) {
        for (int index = treeIndex; index < weightTree.length;
                index += Integer.lowestOneBit(index)) {
            weightTree[index] = Math.subtractExact(weightTree[index], weight);
        }
    }
}

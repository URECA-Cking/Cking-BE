package kr.co.cking.drawing.domain.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;

final class WeightedScenarios {

    enum Distribution { UNIFORM_1, UNIFORM_100, SKEWED_1000, UNIFORM_MILLION }

    record Scenario(String name, List<CandidateValue> raw, Set<Long> excluded,
                    DrawingSeed seed, int winnerCount) {
        WeightedComparison.Prepared prepare() {
            return WeightedComparison.normalize(raw, excluded, seed, winnerCount);
        }
    }

    static List<Scenario> create(DrawingSeed seed) {
        List<Scenario> scenarios = new ArrayList<>();
        for (int size : new int[] {1_000, 10_000}) {
            int eligible = size - size / 20;
            for (Distribution distribution : Distribution.values()) {
                int[] counts = distribution == Distribution.UNIFORM_MILLION
                        ? new int[] {eligible / 10} : new int[] {1, eligible / 100, eligible / 10};
                for (int winners : counts) {
                    scenarios.add(create(size, winners, distribution, seed));
                }
            }
        }
        return List.copyOf(scenarios);
    }

    static Scenario create(int size, int winners, Distribution distribution, DrawingSeed seed) {
        List<CandidateValue> candidates = new ArrayList<>(size);
        Set<Long> excluded = new HashSet<>();
        for (long id = 1; id <= size; id++) {
            long weight = switch (distribution) {
                case UNIFORM_1 -> 1;
                case UNIFORM_100 -> 100;
                case SKEWED_1000 -> id % 10 == 1 ? 1_000 : 1;
                case UNIFORM_MILLION -> 1_000_000;
            };
            candidates.add(new CandidateValue(id, weight));
            if (id % 20 == 0) {
                excluded.add(id);
            }
        }
        // 준비 입력은 모든 방식에서 동일한 비정렬 순서이며 측정 중 생성하지 않는다.
        Collections.shuffle(candidates, new Random(503));
        return new Scenario("N" + size + "_K" + winners + "_" + distribution,
                List.copyOf(candidates), Set.copyOf(excluded), seed, winners);
    }
}

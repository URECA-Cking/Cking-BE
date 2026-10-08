package kr.co.cking.drawing.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.co.cking.snapshot.domain.CandidateValue;

/** Fenwick Tree와 운영 난수를 사용하지 않고 가능한 순서의 확률을 곱해 열거한다. */
public final class WithoutReplacementReference {
    private WithoutReplacementReference() {
    }

    public static Map<List<Long>, Double> orderedProbabilities(SimulationScenario scenario) {
        Map<List<Long>, Double> result = new LinkedHashMap<>();
        enumerate(scenario.eligible(), scenario.winnerCount(), List.of(), 1.0, result);
        return result;
    }

    private static void enumerate(List<CandidateValue> remaining, int count, List<Long> prefix,
                                  double probability, Map<List<Long>, Double> result) {
        if (count == 0) {
            result.put(List.copyOf(prefix), probability);
            return;
        }
        long total = 0;
        for (CandidateValue candidate : remaining) {
            total = Math.addExact(total, candidate.ticketCount());
        }
        for (CandidateValue candidate : remaining) {
            List<Long> next = new ArrayList<>(prefix);
            next.add(candidate.memberId());
            enumerate(remaining.stream().filter(c -> !c.memberId().equals(candidate.memberId())).toList(),
                    count - 1, next, probability * ((double) candidate.ticketCount() / total), result);
        }
    }

    public static Map<Long, Double> inclusionProbabilities(SimulationScenario scenario) {
        Map<Long, Double> result = new LinkedHashMap<>();
        if (scenario.winnerCount() == scenario.eligible().size()) {
            scenario.eligible().forEach(c -> result.put(c.memberId(), 1.0));
            return result;
        }
        scenario.eligible().forEach(c -> result.put(c.memberId(), 0.0));
        orderedProbabilities(scenario).forEach((order, probability) ->
                order.forEach(id -> result.merge(id, probability, Double::sum)));
        return result;
    }
}

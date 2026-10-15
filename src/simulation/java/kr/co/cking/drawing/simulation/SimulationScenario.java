package kr.co.cking.drawing.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;

public record SimulationScenario(String id, List<CandidateValue> candidates,
                                 int winnerCount, Set<Long> excludedMemberIds) {
    public SimulationScenario {
        if (id == null || !id.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("시나리오 ID는 소문자 영숫자와 하이픈이어야 합니다.");
        }
        candidates = List.copyOf(candidates);
        excludedMemberIds = Set.copyOf(excludedMemberIds);
        // 운영 입력 계약과 제외 후 가중치 합을 실행 전에 확인한다.
        input(DrawingSeed.from("00".repeat(32)), candidates, winnerCount, excludedMemberIds);
        long total = 0;
        int eligibleCount = 0;
        for (CandidateValue candidate : candidates) {
            if (!excludedMemberIds.contains(candidate.memberId())) {
                total = Math.addExact(total, candidate.ticketCount());
                eligibleCount++;
            }
        }
        if (winnerCount > eligibleCount || eligibleCount > 8) {
            throw new IllegalArgumentException("열거 기준값은 제외 후 8명 이하이며 당첨자 수가 유효해야 합니다.");
        }
    }

    public DrawInput input(DrawingSeed seed) {
        return input(seed, candidates, winnerCount, excludedMemberIds);
    }

    private static DrawInput input(DrawingSeed seed, List<CandidateValue> candidates,
                                   int winnerCount, Set<Long> excludedMemberIds) {
        return new DrawInput(504L, 504L, "00".repeat(32), seed, "WEIGHTED_V1",
                winnerCount, candidates, excludedMemberIds);
    }

    public List<CandidateValue> eligible() {
        return candidates.stream().filter(c -> !excludedMemberIds.contains(c.memberId())).toList();
    }

    // 표본 수, 후보, 기준은 결과를 보기 전에 고정한다. 큰 합은 u + 3u + 6u = 10u = Long.MAX_VALUE - 7이다.
    public static List<SimulationScenario> standardScenarios() {
        long unit = Long.MAX_VALUE / 10;
        return List.of(
                of("equal", 1, 1, 1, 1, 1),
                of("asymmetric", 1, 1, 3, 6),
                of("rare", 1, 1, 999_999, 9_000_000),
                new SimulationScenario("large-total", List.of(
                        new CandidateValue(1L, unit), new CandidateValue(2L, 3 * unit),
                        new CandidateValue(3L, 6 * unit)), 1, Set.of()),
                of("multi-two", 2, 1, 3, 6),
                new SimulationScenario("multi-three-excluded", List.of(
                        new CandidateValue(1L, 1), new CandidateValue(2L, 2),
                        new CandidateValue(3L, 3), new CandidateValue(4L, 4),
                        new CandidateValue(5L, Long.MAX_VALUE)), 3, Set.of(5L))
        );
    }

    public static SimulationScenario of(String id, int winnerCount, long... weights) {
        List<CandidateValue> candidates = new ArrayList<>();
        for (int index = 0; index < weights.length; index++) {
            candidates.add(new CandidateValue((long) index + 1, weights[index]));
        }
        return new SimulationScenario(id, candidates, winnerCount, Set.of());
    }
}

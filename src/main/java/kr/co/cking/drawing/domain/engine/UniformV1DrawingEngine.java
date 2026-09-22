package kr.co.cking.drawing.domain.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.snapshot.domain.CandidateValue;

/** 응모권 수와 무관하게 각 후보를 동일한 확률로 복원 없이 선정하는 UNIFORM_V1 구현체. */
public final class UniformV1DrawingEngine implements DrawingEngine {

    @Override
    public DrawOutput draw(DrawInput input) {
        if (input == null) {
            throw new IllegalArgumentException("DrawInput은 필수입니다.");
        }

        DrawingAlgorithmVersion algorithmVersion = DrawingAlgorithmVersion.from(input.algorithmVersion());
        if (algorithmVersion != DrawingAlgorithmVersion.UNIFORM_V1) {
            throw new IllegalArgumentException("UniformV1DrawingEngine은 UNIFORM_V1만 지원합니다.");
        }

        List<CandidateValue> pool = new ArrayList<>(input.candidates().stream()
                .filter(candidate -> !input.excludedMemberIds().contains(candidate.memberId()))
                .toList());
        if (input.winnerCount() > pool.size()) {
            throw new IllegalArgumentException("제외 대상을 반영한 후보 수보다 winnerCount가 클 수 없습니다.");
        }

        DeterministicRandom random = new DeterministicRandom(input.seed());
        List<DrawWinner> winners = new ArrayList<>(input.winnerCount());
        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            int selectedIndex = Math.toIntExact(random.nextLong(pool.size()));
            int lastIndex = pool.size() - 1;
            CandidateValue winner = pool.get(selectedIndex);
            Collections.swap(pool, selectedIndex, lastIndex);
            pool.remove(lastIndex);
            winners.add(new DrawWinner(winner.memberId(), rank, winner.ticketCount()));
        }
        return new DrawOutput(DrawingAlgorithmVersion.UNIFORM_V1, winners);
    }
}

package kr.co.cking.drawing.domain.hash;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;

/** 당첨자별 상품 배정까지 포함하는 결과 Hash V2다. */
public final class DrawResultV2HashGenerator {
    public DrawingHash generate(String inputHash, DrawOutput output, PrizeAllocationOutput prizeOutput) {
        return Sha256Hash.from(canonicalize(inputHash, output, prizeOutput));
    }

    public String canonicalize(String inputHash, DrawOutput output, PrizeAllocationOutput prizeOutput) {
        if (inputHash == null || !inputHash.matches("[0-9a-f]{64}") || output == null || prizeOutput == null) {
            throw new IllegalArgumentException("DrawResult V2 Hash 입력이 올바르지 않습니다.");
        }
        Map<Integer, AllocatedPrize> byRank = prizeOutput.allocations().stream()
                .collect(Collectors.toMap(AllocatedPrize::rank, Function.identity()));
        StringBuilder payload = new StringBuilder().append("CKING_DRAW_RESULT_V2\n")
                .append("inputHash=").append(inputHash).append('\n')
                .append("algorithmVersion=").append(output.algorithmVersion().name()).append('\n')
                .append("prizeAlgorithmVersion=").append(prizeOutput.algorithmVersion().name()).append('\n')
                .append("winners\n");
        output.winners().stream().sorted(Comparator.comparingInt(winner -> winner.rank())).forEach(winner -> {
            AllocatedPrize allocation = byRank.get(winner.rank());
            if (allocation == null || !allocation.memberId().equals(winner.memberId())) {
                throw new IllegalArgumentException("당첨자와 상품 배정 결과가 일치하지 않습니다.");
            }
            payload.append(winner.rank()).append(',').append(winner.memberId()).append(',')
                    .append(winner.appliedTicketCount()).append(',')
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            allocation.prize().prizeKey().getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        });
        return payload.toString();
    }
}

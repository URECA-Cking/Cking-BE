package kr.co.cking.drawing.domain.hash;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;

/**
 * 추첨 결과를 입력 Hash와 결합해 정규화하고 SHA-256 Hash를 생성한다.
 */
public final class DrawResultHashGenerator {

    private static final String FORMAT_VERSION = "CKING_DRAW_RESULT_V1";
    private static final Comparator<DrawWinner> BY_RANK = Comparator.comparingInt(DrawWinner::rank);

    public DrawingHash generate(String inputHash, DrawOutput output) {
        return Sha256Hash.from(canonicalize(inputHash, output));
    }

    public String canonicalize(String inputHash, DrawOutput output) {
        validateInputHash(inputHash);
        List<DrawWinner> winners = normalizedWinners(output);

        StringBuilder payload = new StringBuilder()
                .append(FORMAT_VERSION).append('\n')
                .append("inputHash=").append(inputHash).append('\n')
                .append("algorithmVersion=").append(output.algorithmVersion().name()).append('\n')
                .append("winners").append('\n');

        winners.forEach(winner -> payload
                .append(winner.rank())
                .append(',')
                .append(winner.memberId())
                .append(',')
                .append(winner.appliedTicketCount())
                .append('\n'));
        return payload.toString();
    }

    private void validateInputHash(String inputHash) {
        if (inputHash == null || !inputHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inputHash는 SHA-256 lowercase hex여야 합니다.");
        }
    }

    private List<DrawWinner> normalizedWinners(DrawOutput output) {
        if (output == null) {
            throw new IllegalArgumentException("DrawOutput Hash 입력은 필수입니다.");
        }

        Set<Integer> ranks = new HashSet<>();
        Set<Long> memberIds = new HashSet<>();
        for (DrawWinner winner : output.winners()) {
            if (!ranks.add(winner.rank())) {
                throw new IllegalArgumentException("Winner의 rank는 중복될 수 없습니다.");
            }
            if (!memberIds.add(winner.memberId())) {
                throw new IllegalArgumentException("Winner의 memberId는 중복될 수 없습니다.");
            }
        }
        return output.winners().stream()
                .sorted(BY_RANK)
                .toList();
    }
}

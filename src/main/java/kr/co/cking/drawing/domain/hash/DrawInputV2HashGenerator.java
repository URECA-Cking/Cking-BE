package kr.co.cking.drawing.domain.hash;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.snapshot.domain.PrizeValue;

/** 기존 V1을 유지하면서 상품 Snapshot과 상품 배정 알고리즘을 포함하는 입력 Hash V2다. */
public final class DrawInputV2HashGenerator {
    public DrawingHash generate(DrawInput input, String prizeAlgorithmVersion,
                                java.util.List<PrizeValue> prizes) {
        return Sha256Hash.from(canonicalize(input, prizeAlgorithmVersion, prizes));
    }

    public String canonicalize(DrawInput input, String prizeAlgorithmVersion,
                               java.util.List<PrizeValue> prizes) {
        if (input == null || prizeAlgorithmVersion == null || prizeAlgorithmVersion.isBlank() || prizes == null) {
            throw new IllegalArgumentException("DrawInput V2 Hash 입력은 필수입니다.");
        }
        StringBuilder payload = new StringBuilder()
                .append("CKING_DRAW_INPUT_V2\n")
                .append("eventId=").append(input.eventId()).append('\n')
                .append("snapshotId=").append(input.snapshotId()).append('\n')
                .append("snapshotHash=").append(input.snapshotHash()).append('\n')
                .append("seed=").append(input.seed().value()).append('\n')
                .append("algorithmVersion=").append(input.algorithmVersion()).append('\n')
                .append("prizeAlgorithmVersion=").append(prizeAlgorithmVersion).append('\n')
                .append("winnerCount=").append(input.winnerCount()).append('\n')
                .append("candidates\n");
        input.candidates().forEach(candidate -> payload.append(candidate.memberId()).append(',')
                .append(candidate.ticketCount()).append('\n'));
        payload.append("excludedMemberIds\n");
        input.excludedMemberIds().stream().sorted().forEach(id -> payload.append(id).append('\n'));
        payload.append("prizes\n");
        prizes.stream().sorted(java.util.Comparator.comparingInt(PrizeValue::priority)
                        .thenComparing(PrizeValue::prizeKey))
                .forEach(prize -> payload.append(encode(prize.prizeKey())).append(',')
                        .append(encode(prize.displayName())).append(',').append(prize.priority()).append(',')
                        .append(prize.weight()).append(',').append(prize.quantity()).append('\n'));
        return payload.toString();
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

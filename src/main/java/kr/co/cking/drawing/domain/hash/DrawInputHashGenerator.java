package kr.co.cking.drawing.domain.hash;

import kr.co.cking.drawing.domain.engine.DrawInput;

/**
 * 추첨 입력을 버전이 고정된 문자열로 정규화하고 SHA-256 Hash를 생성한다.
 */
public final class DrawInputHashGenerator {

    private static final String FORMAT_VERSION = "CKING_DRAW_INPUT_V1";

    public DrawingHash generate(DrawInput input) {
        return Sha256Hash.from(canonicalize(input));
    }

    public String canonicalize(DrawInput input) {
        if (input == null) {
            throw new IllegalArgumentException("DrawInput Hash 입력은 필수입니다.");
        }

        StringBuilder payload = new StringBuilder()
                .append(FORMAT_VERSION).append('\n')
                .append("eventId=").append(input.eventId()).append('\n')
                .append("snapshotId=").append(input.snapshotId()).append('\n')
                .append("snapshotHash=").append(input.snapshotHash()).append('\n')
                .append("seed=").append(input.seed().value()).append('\n')
                .append("algorithmVersion=").append(input.algorithmVersion()).append('\n')
                .append("winnerCount=").append(input.winnerCount()).append('\n')
                .append("candidates").append('\n');

        input.candidates().forEach(candidate -> payload
                .append(candidate.memberId())
                .append(',')
                .append(candidate.ticketCount())
                .append('\n'));

        payload.append("excludedMemberIds").append('\n');
        input.excludedMemberIds().stream()
                .sorted()
                .forEach(memberId -> payload.append(memberId).append('\n'));
        return payload.toString();
    }
}

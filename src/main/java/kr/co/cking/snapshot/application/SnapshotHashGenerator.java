package kr.co.cking.snapshot.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.springframework.stereotype.Component;

@Component
public class SnapshotHashGenerator {

    private static final String FORMAT_VERSION = "CKING_SNAPSHOT_V1";

    public SnapshotHash generate(SnapshotHashInput input) {
        String canonicalPayload = canonicalize(input);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return new SnapshotHash(canonicalPayload, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    public String canonicalize(SnapshotHashInput input) {
        validate(input);

        StringBuilder payload = new StringBuilder()
                .append(FORMAT_VERSION).append('\n')
                .append("eventId=").append(input.eventId()).append('\n')
                .append("winnerCount=").append(input.winnerCount()).append('\n')
                .append("drawMethod=").append(input.drawMethod()).append('\n')
                .append("algorithmVersion=").append(input.algorithmVersion()).append('\n')
                .append("candidates").append('\n');

        input.candidates().forEach(candidate -> payload
                .append(candidate.memberId())
                .append(',')
                .append(candidate.ticketCount())
                .append('\n'));
        return payload.toString();
    }

    private void validate(SnapshotHashInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Snapshot Hash 입력은 필수입니다.");
        }
        if (input.eventId() == null || input.eventId() <= 0) {
            throw new IllegalArgumentException("eventId는 양수여야 합니다.");
        }
        if (input.winnerCount() <= 0) {
            throw new IllegalArgumentException("winnerCount는 양수여야 합니다.");
        }
        if (input.drawMethod() == null || input.drawMethod().isBlank()) {
            throw new IllegalArgumentException("drawMethod는 필수입니다.");
        }
        if (input.algorithmVersion() == null || input.algorithmVersion().isBlank()) {
            throw new IllegalArgumentException("algorithmVersion은 필수입니다.");
        }
        HashSet<Long> memberIds = new HashSet<>();
        for (CandidateValue candidate : input.candidates()) {
            if (!memberIds.add(candidate.memberId())) {
                throw new IllegalArgumentException("Snapshot 후보의 memberId는 중복될 수 없습니다.");
            }
        }
    }
}

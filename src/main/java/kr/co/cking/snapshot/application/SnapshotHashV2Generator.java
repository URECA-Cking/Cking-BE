package kr.co.cking.snapshot.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import kr.co.cking.snapshot.domain.PrizeValue;
import org.springframework.stereotype.Component;

/** 기존 V1 계약을 변경하지 않고 상품 설정을 추가한 Snapshot V2 Hash를 생성한다. */
@Component
public class SnapshotHashV2Generator {
    private static final String FORMAT_VERSION = "CKING_SNAPSHOT_V2";

    public SnapshotHash generate(SnapshotHashV2Input input) {
        String payload = canonicalize(input);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return new SnapshotHash(payload, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    public String canonicalize(SnapshotHashV2Input input) {
        validate(input);
        StringBuilder payload = new StringBuilder()
                .append(FORMAT_VERSION).append('\n')
                .append("eventId=").append(input.eventId()).append('\n')
                .append("winnerCount=").append(input.winnerCount()).append('\n')
                .append("drawMethod=").append(input.drawMethod()).append('\n')
                .append("algorithmVersion=").append(input.algorithmVersion()).append('\n')
                .append("prizeAlgorithmVersion=").append(input.prizeAlgorithmVersion()).append('\n')
                .append("candidates").append('\n');
        input.candidates().forEach(candidate -> payload.append(candidate.memberId()).append(',')
                .append(candidate.ticketCount()).append('\n'));
        payload.append("prizes").append('\n');
        input.prizes().forEach(prize -> payload.append(encode(prize.prizeKey())).append(',')
                .append(encode(prize.displayName())).append(',')
                .append(prize.priority()).append(',').append(prize.weight()).append(',')
                .append(prize.quantity()).append('\n'));
        return payload.toString();
    }

    private void validate(SnapshotHashV2Input input) {
        if (input == null || input.eventId() == null || input.eventId() <= 0 || input.winnerCount() <= 0
                || input.drawMethod() == null || input.drawMethod().isBlank()
                || input.algorithmVersion() == null || input.algorithmVersion().isBlank()
                || input.prizeAlgorithmVersion() == null || input.prizeAlgorithmVersion().isBlank()) {
            throw new IllegalArgumentException("Snapshot V2 Hash 필수 입력이 올바르지 않습니다.");
        }
        HashSet<Long> members = new HashSet<>();
        input.candidates().forEach(candidate -> {
            if (!members.add(candidate.memberId())) {
                throw new IllegalArgumentException("후보 memberId는 중복될 수 없습니다.");
            }
        });
        HashSet<String> keys = new HashSet<>();
        long totalQuantity = 0;
        long totalWeight = 0;
        for (PrizeValue prize : input.prizes()) {
            if (!keys.add(prize.prizeKey())) {
                throw new IllegalArgumentException("상품 식별자는 중복될 수 없습니다.");
            }
            totalQuantity = Math.addExact(totalQuantity, prize.quantity());
            totalWeight = Math.addExact(totalWeight, prize.weight());
        }
        if (totalQuantity < input.winnerCount()) {
            throw new IllegalArgumentException("총 상품 수량은 winnerCount 이상이어야 합니다.");
        }
    }

    public static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

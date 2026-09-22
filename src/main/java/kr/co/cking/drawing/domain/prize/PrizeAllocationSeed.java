package kr.co.cking.drawing.domain.prize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import kr.co.cking.drawing.domain.seed.DrawingSeed;

/** 후보 선정 난수 흐름과 상품 배정 난수 흐름을 분리하는 파생 Seed 정책이다. */
final class PrizeAllocationSeed {
    private static final byte[] DOMAIN = "CKING_PRIZE_ALLOCATION_V1".getBytes(StandardCharsets.UTF_8);

    private PrizeAllocationSeed() {
    }

    static DrawingSeed derive(DrawingSeed seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed.bytes());
            digest.update(DOMAIN);
            return DrawingSeed.fromBytes(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}

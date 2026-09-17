package kr.co.cking.drawing.domain.seed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 동일 Seed에서 동일 난수열을 생성하는 추첨 전용 난수 생성기.
 *
 * <p>하나의 인스턴스를 하나의 추첨 실행에서 순차적으로 사용한다.
 */
public final class DeterministicRandom {

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte[] FORMAT_VERSION =
            "CKING_DRAW_RANDOM_V1".getBytes(StandardCharsets.US_ASCII);

    private final Mac mac;
    private long counter;
    private byte[] block = new byte[0];
    private int blockPosition;

    public DeterministicRandom(DrawingSeed seed) {
        if (seed == null) {
            throw new IllegalArgumentException("Seed는 필수입니다.");
        }
        this.mac = initializeMac(seed);
    }

    public long nextLong() {
        byte[] value = new byte[Long.BYTES];
        for (int index = 0; index < value.length; index++) {
            value[index] = nextByte();
        }
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound는 양수여야 합니다.");
        }

        long mask = bound - 1;
        long random = nextLong();
        if ((bound & mask) == 0) {
            return random & mask;
        }

        // 나머지 연산의 모듈러 편향 제거를 위한 범위 밖 난수 재추출.
        long candidate = random >>> 1;
        long result = candidate % bound;
        while (candidate + mask - result < 0) {
            candidate = nextLong() >>> 1;
            result = candidate % bound;
        }
        return result;
    }

    private byte nextByte() {
        if (blockPosition == block.length) {
            block = nextBlock();
            blockPosition = 0;
        }
        return block[blockPosition++];
    }

    private byte[] nextBlock() {
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("결정적 난수 생성 한도를 초과했습니다.");
        }

        byte[] counterBytes = ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(counter++)
                .array();

        // JVM 기본 PRNG 구현 차이 배제를 위한 버전 고정 HMAC 난수 블록 생성.
        mac.update(FORMAT_VERSION);
        return mac.doFinal(counterBytes);
    }

    private Mac initializeMac(DrawingSeed seed) {
        try {
            Mac initializedMac = Mac.getInstance(MAC_ALGORITHM);
            initializedMac.init(new SecretKeySpec(seed.bytes(), MAC_ALGORITHM));
            return initializedMac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}

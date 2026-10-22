package kr.co.cking.drawing.domain.seed;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class SecureDrawingSeedGenerator implements DrawingSeedGenerator {

    private final SecureRandom secureRandom;

    public SecureDrawingSeedGenerator() {
        this(new SecureRandom());
    }

    SecureDrawingSeedGenerator(SecureRandom secureRandom) {
        if (secureRandom == null) {
            throw new IllegalArgumentException("SecureRandom은 필수입니다.");
        }
        this.secureRandom = secureRandom;
    }

    @Override
    public DrawingSeed generate() {
        byte[] seedBytes = new byte[DrawingSeed.BYTE_LENGTH];
        secureRandom.nextBytes(seedBytes);
        return new DrawingSeed(HexFormat.of().formatHex(seedBytes));
    }
}

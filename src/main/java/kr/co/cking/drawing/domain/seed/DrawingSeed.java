package kr.co.cking.drawing.domain.seed;

import java.util.HexFormat;
import java.util.regex.Pattern;

public record DrawingSeed(String value) {

    public static final int BYTE_LENGTH = 32;
    public static final int TEXT_LENGTH = BYTE_LENGTH * 2;

    private static final Pattern CANONICAL_FORMAT = Pattern.compile("[0-9a-f]{" + TEXT_LENGTH + "}");

    public DrawingSeed {
        if (value == null || !CANONICAL_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Seed는 " + TEXT_LENGTH + "자리 소문자 16진수여야 합니다."
            );
        }
    }

    public static DrawingSeed from(String value) {
        return new DrawingSeed(value);
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(value);
    }
}

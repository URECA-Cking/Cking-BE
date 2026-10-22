package kr.co.cking.abuse.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Mission Business Key와 Detection Cooldown Scope에 공통으로 적용하는 SHA-256 hash 계약이다. */
public final class AbuseScopeHash {

    private static final Pattern SHA_256_LOWERCASE_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** 정적 hash 유틸리티의 인스턴스 생성을 막는다. */
    private AbuseScopeHash() {
    }

    /** canonical scope 원문을 UTF-8 SHA-256 lowercase hex로 변환한다. */
    public static String fromCanonicalValue(String canonicalValue) {
        requireCanonicalValue(canonicalValue);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    /** 이미 생성된 hash가 Redis Key와 DetectionResult에 쓸 수 있는 형식인지 검증한다. */
    public static void requireValidHash(String hash, String name) {
        if (hash == null || !SHA_256_LOWERCASE_HEX.matcher(hash).matches()) {
            throw new IllegalArgumentException(name + "는 lowercase SHA-256 hex여야 합니다.");
        }
    }

    /** hash의 입력이 되는 canonical scope 원문이 비어 있지 않은지 검증한다. */
    private static void requireCanonicalValue(String canonicalValue) {
        if (canonicalValue == null || canonicalValue.isBlank()) {
            throw new IllegalArgumentException("canonicalValue는 null 또는 blank일 수 없습니다.");
        }
    }
}

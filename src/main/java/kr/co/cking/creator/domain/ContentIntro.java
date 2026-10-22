package kr.co.cking.creator.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;

/** Python content-tags-v1: CRLF/CR→LF, NFC, UTF-8 SHA-256, no trimming. */
public final class ContentIntro {
    private ContentIntro() {}
    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n"), Normalizer.Form.NFC);
    }
    public static String hash(String text) { return sha256(normalize(text)); }
    public static String sha256(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired Unicode surrogate");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

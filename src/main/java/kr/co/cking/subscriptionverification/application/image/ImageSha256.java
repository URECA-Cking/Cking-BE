package kr.co.cking.subscriptionverification.application.image;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ImageSha256 {

    private ImageSha256() {}

    public static String calculate(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}

package kr.co.cking.member.domain;

import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** Member 공개 닉네임의 정규화·형식·변경 주기 정책이다. */
public record MemberNickname(String value, String normalized) {

    public static final int MAX_LENGTH = 20;
    public static final String REGEX = "^[가-힣A-Za-z0-9_-]{1,20}$";
    public static final Duration CHANGE_INTERVAL = Duration.ofDays(14);
    public static final Duration RESERVATION_PERIOD = Duration.ofDays(14);

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public MemberNickname {
        if (!isValid(value) || normalized == null || !normalized.equals(normalize(value))) {
            throw new IllegalArgumentException("닉네임 형식 또는 정규화 값이 올바르지 않습니다.");
        }
    }

    public static MemberNickname of(String nickname) {
        String nfc = nickname == null ? null : Normalizer.normalize(nickname, Normalizer.Form.NFC);
        if (!isValid(nfc)) {
            throw new IllegalArgumentException("닉네임 형식이 올바르지 않습니다.");
        }
        return new MemberNickname(nfc, normalize(nfc));
    }

    public static boolean isValid(String nickname) {
        return nickname != null && PATTERN.matcher(nickname).matches();
    }

    public static String normalize(String nickname) {
        return Normalizer.normalize(nickname, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}

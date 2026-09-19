package kr.co.cking.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 공개 응답에서 개인정보를 원본 변경 없이 마스킹하는 보조 도구다. */
public final class PersonalInfoMasker {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\d{2,3})-\\d{3,4}-(\\d{4})$");
    private static final String UNKNOWN_PHONE_MASK = "****";

    /** 인스턴스 생성을 막아 정적 마스킹 기능만 제공한다. */
    private PersonalInfoMasker() {
    }

    /** 이름의 성과 마지막 글자만 남기고, 두 글자 이름은 마지막 글자를 가려 공개한다. */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return "*";
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    /** 전화번호를 마스킹하고 형식을 판별할 수 없으면 원문 대신 고정 마스킹값을 반환한다. */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        Matcher matcher = PHONE_PATTERN.matcher(phone);
        if (!matcher.matches()) {
            return UNKNOWN_PHONE_MASK;
        }
        return matcher.group(1) + "-****-" + matcher.group(2);
    }
}

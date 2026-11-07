package kr.co.cking.post.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 필터 서비스가 준 판정 사유를 로그나 응답에 내보낼 수 있는 값으로 정리한다. 사유는 외부 입력이라 그대로 내보내지 않는다.
 *
 * <p>유형이 {@link #REASON_TYPES}에 있을 때만 남기고, 아니면 {@value #INVALID_VALUE}다. 이름({@code 유형:이름})은 영문 소문자와
 * 밑줄뿐일 때만 남기고 아니면 유형만 남긴다. 숫자·한글·공백이 든 이름에는 전화번호나 댓글 문장이 들어올 수 있어서다.
 */
public final class CommentFilterReasons {

    public static final String INVALID_VALUE = "invalid";
    /** 내보내도 되는 사유 유형. 필터 서비스의 규칙 코드 유형이다. */
    private static final Set<String> REASON_TYPES = Set.of("classifier", "model", "profanity", "privacy", "spam");
    /** 사유 이름. 영문 소문자와 밑줄뿐이라 숫자·한글·공백이 든 개인정보나 댓글 문장은 걸러진다. */
    private static final Pattern REASON_NAME = Pattern.compile("^[a-z_]{1,30}$");

    private CommentFilterReasons() {
    }

    public static String sanitize(String reason) {
        int colon = reason.indexOf(':');
        String type = colon < 0 ? reason : reason.substring(0, colon);
        if (!REASON_TYPES.contains(type)) {
            return INVALID_VALUE;
        }
        if (colon < 0) {
            return type;
        }
        String name = reason.substring(colon + 1);
        return REASON_NAME.matcher(name).matches() ? type + ":" + name : type;
    }
}

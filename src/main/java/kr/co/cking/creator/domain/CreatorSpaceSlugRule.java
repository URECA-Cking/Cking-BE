package kr.co.cking.creator.domain;

import java.util.regex.Pattern;

/**
 * 템플릿이 바뀌어도 slug의 끝 숫자열이 creatorId를 식별하도록 규칙을 제한한다.
 * 소문자 ASCII 제한은 DB의 대소문자·악센트 무시 collation과도 일치시킨다.
 */
public final class CreatorSpaceSlugRule {

    public static final String PLACEHOLDER = "{creatorId}";

    public static final String REGEX = "^(?:[a-z0-9-]*[a-z-])?\\{creatorId\\}$";

    public static final String MESSAGE =
            "slugRule은 소문자·숫자·하이픈 접두사 뒤 맨 끝에 {creatorId}를 한 번만 포함해야 하며, 바로 앞 글자는 숫자일 수 없습니다.";

    public static final int MAX_SLUG_LENGTH = 100;

    /** Long 최대 19자리로 치환할 때 VARCHAR(100)을 넘지 않는 입력 길이. */
    public static final int MAX_RULE_LENGTH = 92;

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private CreatorSpaceSlugRule() {
    }

    public static boolean isValid(String slugRule) {
        return slugRule != null && PATTERN.matcher(slugRule).matches();
    }

    public static String apply(String slugRule, Long creatorId) {
        return slugRule.replace(PLACEHOLDER, String.valueOf(creatorId));
    }
}

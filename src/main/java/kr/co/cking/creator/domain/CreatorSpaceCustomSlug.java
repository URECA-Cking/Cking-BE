package kr.co.cking.creator.domain;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creator가 직접 정하는 커스텀 slug 규칙이다(이슈 #290). 정책 정본은
 * docs/domains/creator/space-slug-policy.md다.
 *
 * <p>소문자 ASCII로 제한하는 이유는 slug 컬럼 collation(utf8mb4_0900_ai_ci)이 대소문자·악센트를
 * 같은 값으로 비교하기 때문이다. 중복 여부는 DB UNIQUE 제약이 최종 판단한다.
 */
public final class CreatorSpaceCustomSlug {

    /** 3~30자, 소문자·숫자·하이픈·밑줄, 처음과 끝은 소문자나 숫자. */
    public static final String REGEX = "^[a-z0-9][a-z0-9_-]{1,28}[a-z0-9]$";

    /** 마지막 변경 후 다시 바꿀 수 있을 때까지의 간격. 첫 변경(자동 slug → 커스텀)은 제한하지 않는다. */
    public static final Duration CHANGE_INTERVAL = Duration.ofDays(14);

    public static final String MESSAGE =
            "slug는 3~30자의 소문자·숫자·하이픈(-)·밑줄(_)이며, 처음과 끝은 소문자나 숫자여야 합니다.";

    /** 서비스 경로·운영 용어와 겹쳐 사용자를 혼동시킬 수 있는 값이다. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "api", "auth", "login", "logout", "signup", "oauth",
            "me", "my", "settings", "help", "support", "notice", "system", "root",
            "cking", "official", "staff", "manager",
            "creator", "creators", "space", "spaces", "mission", "missions",
            "event", "events", "ticket", "tickets", "winner", "winners", "notification", "notifications",
            "null", "undefined"
    );

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private CreatorSpaceCustomSlug() {
    }

    public static boolean isValidFormat(String slug) {
        return slug != null && PATTERN.matcher(slug).matches();
    }

    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }
}

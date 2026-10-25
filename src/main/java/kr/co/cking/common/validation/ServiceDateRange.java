package kr.co.cking.common.validation;

import java.time.Instant;

/**
 * 사용자가 입력하는 이벤트·일정 날짜가 받을 수 있는 범위다. 어느 한 시간대에서라도 2000~2099년에 해당하면 받는다
 * (UTC+14의 2000-01-01 0시 이상, UTC-12의 2100-01-01 0시 미만).
 * DB DATETIME은 9999년까지만 저장하고 브라우저 날짜 입력은 다섯 자리 연도도 보내므로, 검사가 없으면 저장 단계에서 500이 난다.
 */
public final class ServiceDateRange {

    /** 2000-01-01T00:00 (UTC+14). */
    public static final Instant MIN = Instant.parse("1999-12-31T10:00:00Z");

    /** 2100-01-01T00:00 (UTC-12). 시작 시각은 이보다 앞서야 하고, 끝 시각은 이 시각까지 된다. */
    public static final Instant MAX_EXCLUSIVE = Instant.parse("2100-01-01T12:00:00Z");

    private ServiceDateRange() {
    }

    public static boolean contains(Instant value) {
        return value != null && !value.isBefore(MIN) && value.isBefore(MAX_EXCLUSIVE);
    }

    /** 끝 시각은 포함하지 않는 경계라 상한과 같아도 받는다(예: UTC-12의 2099-12-31 종일 일정은 2100-01-01T12:00Z에 끝난다). */
    public static boolean containsEnd(Instant value) {
        return value != null && !value.isBefore(MIN) && !value.isAfter(MAX_EXCLUSIVE);
    }

    /** 조회 구간 [from, to)가 이 범위와 겹치는지. 겹치지 않으면 범위를 걸친 기존 값이 경계로 잘린 구간에 잘못 걸리지 않도록 조회하지 않는다. */
    public static boolean overlaps(Instant from, Instant to) {
        return from.isBefore(MAX_EXCLUSIVE) && to.isAfter(MIN);
    }

    /** 기간 조회의 끝값을 이 범위 안으로 자른다. 범위 안에 저장된 값의 조회 결과는 같고, DB가 표현하지 못하는 시각을 보내지 않는다. */
    public static Instant clamp(Instant value) {
        if (value.isBefore(MIN)) {
            return MIN;
        }
        return value.isAfter(MAX_EXCLUSIVE) ? MAX_EXCLUSIVE : value;
    }
}

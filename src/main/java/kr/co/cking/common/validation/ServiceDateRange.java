package kr.co.cking.common.validation;

import java.time.Instant;

/**
 * 사용자가 입력하는 이벤트·일정 날짜가 받을 수 있는 범위다. 어느 시간대 기준으로 보아도 2000~2099년 안이어야 한다.
 * DB DATETIME은 9999년까지만 저장하고 브라우저 날짜 입력은 다섯 자리 연도도 보내므로, 검사가 없으면 저장 단계에서 500이 난다.
 */
public final class ServiceDateRange {

    /** 2000-01-01T00:00 (UTC+14). */
    public static final Instant MIN = Instant.parse("1999-12-31T10:00:00Z");

    /** 2100-01-01T00:00 (UTC-12). 이 시각은 포함하지 않는다. */
    public static final Instant MAX_EXCLUSIVE = Instant.parse("2100-01-01T12:00:00Z");

    private ServiceDateRange() {
    }

    public static boolean contains(Instant value) {
        return value != null && !value.isBefore(MIN) && value.isBefore(MAX_EXCLUSIVE);
    }
}

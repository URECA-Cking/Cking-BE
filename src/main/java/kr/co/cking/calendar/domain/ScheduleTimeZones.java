package kr.co.cking.calendar.domain;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * 일정의 {@code timeZone}을 IANA Time Zone Database 지역명으로만 제한한다.
 * {@link ZoneId#of(String)}는 {@code "UTC"}, {@code "GMT"}, {@code "+09:00"} 같은
 * 고정 오프셋·별칭도 통과시키므로, 순수 지역명 집합인 {@link ZoneId#getAvailableZoneIds()}로
 * 먼저 걸러낸 뒤에만 사용한다. 통과한 값은 {@link ZoneId#getId()}로 정규화해 저장한다.
 */
public final class ScheduleTimeZones {

    private ScheduleTimeZones() {
    }

    public static boolean isValid(String timeZone) {
        if (timeZone == null || timeZone.isBlank() || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            return false;
        }
        try {
            ZoneId.of(timeZone);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    /** {@link #isValid(String)}로 먼저 검증한 값에만 사용한다. */
    public static String normalize(String timeZone) {
        return ZoneId.of(timeZone).getId();
    }
}

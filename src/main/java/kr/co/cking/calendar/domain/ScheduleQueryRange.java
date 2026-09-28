package kr.co.cking.calendar.domain;

import java.time.Duration;
import java.time.Instant;

/** 캘린더 기간 조회(from~to)가 지켜야 할 제약을 검증한다. 페이지 번호 대신 기간으로 조회한다. */
public final class ScheduleQueryRange {

    public static final Duration MAX_RANGE = Duration.ofDays(365);

    private ScheduleQueryRange() {
    }

    public static boolean isValid(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return false;
        }
        return Duration.between(from, to).compareTo(MAX_RANGE) <= 0;
    }
}

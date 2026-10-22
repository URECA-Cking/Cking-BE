package kr.co.cking.calendar.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleQueryRangeTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void from이_to보다_이전이면_유효하다() {
        assertThat(ScheduleQueryRange.isValid(BASE, BASE.plus(30, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void from과_to가_같으면_무효하다() {
        assertThat(ScheduleQueryRange.isValid(BASE, BASE)).isFalse();
    }

    @Test
    void from이_to보다_이후이면_무효하다() {
        assertThat(ScheduleQueryRange.isValid(BASE.plus(1, ChronoUnit.DAYS), BASE)).isFalse();
    }

    @Test
    void 정확히_365일은_유효하다() {
        assertThat(ScheduleQueryRange.isValid(BASE, BASE.plus(365, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void 최대_기간_365일을_초과하면_무효하다() {
        assertThat(ScheduleQueryRange.isValid(BASE, BASE.plus(366, ChronoUnit.DAYS))).isFalse();
    }

    @Test
    void null은_무효하다() {
        assertThat(ScheduleQueryRange.isValid(null, BASE)).isFalse();
        assertThat(ScheduleQueryRange.isValid(BASE, null)).isFalse();
    }
}

package kr.co.cking.calendar.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorScheduleTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void 생성자는_모든_필드와_생성시각을_그대로_저장한다() {
        CreatorSchedule schedule = new CreatorSchedule(
                1L, ScheduleType.FAN_SIGN, "서울 팬사인회", "설명",
                Instant.parse("2026-10-10T05:00:00Z"), Instant.parse("2026-10-10T07:00:00Z"),
                "Asia/Seoul", "서울", "https://img/1.png", "https://example.com", CREATED_AT);

        assertThat(schedule.getCreatorId()).isEqualTo(1L);
        assertThat(schedule.getScheduleType()).isEqualTo(ScheduleType.FAN_SIGN);
        assertThat(schedule.getTitle()).isEqualTo("서울 팬사인회");
        assertThat(schedule.getTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(schedule.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(schedule.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void update은_전체_필드를_새_값으로_교체하고_updatedAt만_갱신한다() {
        CreatorSchedule schedule = new CreatorSchedule(
                1L, ScheduleType.BIRTHDAY, "생일", null,
                Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T01:00:00Z"),
                "Asia/Seoul", null, null, null, CREATED_AT);
        Instant updatedAt = CREATED_AT.plusSeconds(3600);

        schedule.update(
                ScheduleType.BROADCAST, "방송", "새 설명",
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T12:00:00Z"),
                "America/New_York", "온라인", "https://img/2.png", "https://example.com/2", updatedAt);

        assertThat(schedule.getScheduleType()).isEqualTo(ScheduleType.BROADCAST);
        assertThat(schedule.getTitle()).isEqualTo("방송");
        assertThat(schedule.getDescription()).isEqualTo("새 설명");
        assertThat(schedule.getTimeZone()).isEqualTo("America/New_York");
        assertThat(schedule.getLocation()).isEqualTo("온라인");
        assertThat(schedule.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(schedule.getUpdatedAt()).isEqualTo(updatedAt);
    }
}

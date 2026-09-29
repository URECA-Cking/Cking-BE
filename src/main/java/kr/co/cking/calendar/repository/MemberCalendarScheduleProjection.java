package kr.co.cking.calendar.repository;

import kr.co.cking.calendar.domain.ScheduleType;

import java.time.Instant;

/** 개인 캘린더 조회를 위한 MemberCalendarEntry·CreatorSchedule·Creator의 읽기 전용 결합 결과다. */
public record MemberCalendarScheduleProjection(
        Long scheduleId,
        Long creatorId,
        String creatorName,
        ScheduleType scheduleType,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        String timeZone,
        String location,
        String imageUrl,
        String externalUrl
) {
}

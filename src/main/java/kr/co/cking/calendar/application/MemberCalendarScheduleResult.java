package kr.co.cking.calendar.application;

import kr.co.cking.calendar.repository.MemberCalendarScheduleProjection;

import java.time.Instant;

/** 개인 캘린더에 담은 일정을 크리에이터 이름과 함께 외부 API 응답 형식으로 변환한다. */
public record MemberCalendarScheduleResult(
        Long scheduleId,
        Long creatorId,
        String creatorName,
        String scheduleType,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        String timeZone,
        String location,
        String imageUrl,
        String externalUrl
) {

    public static MemberCalendarScheduleResult from(MemberCalendarScheduleProjection projection) {
        return new MemberCalendarScheduleResult(
                projection.scheduleId(),
                projection.creatorId(),
                projection.creatorName(),
                projection.scheduleType().name(),
                projection.title(),
                projection.description(),
                projection.startAt(),
                projection.endAt(),
                projection.timeZone(),
                projection.location(),
                projection.imageUrl(),
                projection.externalUrl()
        );
    }
}

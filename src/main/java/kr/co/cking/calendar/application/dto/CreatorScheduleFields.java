package kr.co.cking.calendar.application.dto;

import kr.co.cking.calendar.domain.ScheduleType;

import java.time.Instant;

/** 크리에이터 일정 생성·수정 요청의 필드를 옮겨 담는다. PATCH는 전체 필드 교체라 생성·수정에 같은 모양을 쓴다. */
public record CreatorScheduleFields(
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

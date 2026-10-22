package kr.co.cking.calendar.presentation.dto;

import kr.co.cking.calendar.domain.CreatorSchedule;

import java.time.Instant;

public final class CreatorScheduleResponse {

    private CreatorScheduleResponse() {
    }

    public record Detail(
            Long scheduleId,
            Long creatorId,
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
        public static Detail from(CreatorSchedule schedule) {
            return new Detail(
                    schedule.getScheduleId(),
                    schedule.getCreatorId(),
                    schedule.getScheduleType().name(),
                    schedule.getTitle(),
                    schedule.getDescription(),
                    schedule.getStartAt(),
                    schedule.getEndAt(),
                    schedule.getTimeZone(),
                    schedule.getLocation(),
                    schedule.getImageUrl(),
                    schedule.getExternalUrl()
            );
        }
    }
}

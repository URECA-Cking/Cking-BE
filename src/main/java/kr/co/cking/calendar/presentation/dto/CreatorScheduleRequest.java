package kr.co.cking.calendar.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cking.calendar.domain.ScheduleType;

import java.time.Instant;

public final class CreatorScheduleRequest {

    private CreatorScheduleRequest() {
    }

    /** PATCH도 이 모양을 그대로 쓴다 — 부분 수정이 아니라 전체 필드 교체다. */
    public record Create(
            @NotNull ScheduleType scheduleType,
            @NotBlank @Size(max = 100) String title,
            @Size(max = 1000) String description,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotBlank @Size(max = 50) String timeZone,
            @Size(max = 200) String location,
            @Size(max = 500) String imageUrl,
            @Size(max = 500) String externalUrl
    ) {
    }

    /** 필드 구성은 {@link Create}와 동일하다. 수정 가능한 모든 필드를 새 값으로 교체하며, 누락 필드는 기존 값 유지가 아니라 검증 실패다. */
    public record Update(
            @NotNull ScheduleType scheduleType,
            @NotBlank @Size(max = 100) String title,
            @Size(max = 1000) String description,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotBlank @Size(max = 50) String timeZone,
            @Size(max = 200) String location,
            @Size(max = 500) String imageUrl,
            @Size(max = 500) String externalUrl
    ) {
    }
}

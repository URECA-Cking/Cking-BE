package kr.co.cking.calendar.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.calendar.application.CreatorScheduleService;
import kr.co.cking.calendar.application.dto.CreatorScheduleFields;
import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.presentation.dto.CreatorScheduleRequest;
import kr.co.cking.calendar.presentation.dto.CreatorScheduleResponse;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.List;

/** 인증된 Creator 본인의 캘린더 일정 생성·수정·삭제·조회 HTTP 요청을 처리한다(이슈 #293). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "크리에이터 캘린더", description = "인증된 Creator 본인의 캘린더 일정 관리 API를 제공합니다.")
public class CreatorScheduleController {

    private final CreatorScheduleService scheduleService;

    @Operation(summary = "크리에이터 일정 생성", description = "인증된 Creator 본인의 일정을 생성합니다. 관리자 승인 없이 즉시 공개됩니다.")
    @PostMapping("/api/creator/calendar/schedules")
    public ResponseEntity<ApiResponse<CreatorScheduleResponse.Detail>> create(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorScheduleRequest.Create request
    ) {
        CreatorSchedule schedule = scheduleService.create(memberId, toFields(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(CreatorScheduleResponse.Detail.from(schedule)));
    }

    @Operation(
            summary = "크리에이터 일정 수정",
            description = "인증된 Creator 본인 소유 일정만 수정할 수 있습니다. 부분 수정이 아니며 모든 필드를 새 값으로 교체합니다."
    )
    @PatchMapping("/api/creator/calendar/schedules/{scheduleId}")
    public ApiResponse<CreatorScheduleResponse.Detail> update(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long scheduleId,
            @Valid @RequestBody CreatorScheduleRequest.Update request
    ) {
        CreatorSchedule schedule = scheduleService.update(memberId, scheduleId, toFields(request));
        return ApiResponse.success(CreatorScheduleResponse.Detail.from(schedule));
    }

    @Operation(summary = "크리에이터 일정 삭제", description = "인증된 Creator 본인 소유 일정만 삭제할 수 있습니다.")
    @DeleteMapping("/api/creator/calendar/schedules/{scheduleId}")
    public ResponseEntity<Void> delete(@CurrentMemberId Long memberId, @PathVariable @Positive Long scheduleId) {
        scheduleService.delete(memberId, scheduleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "내 일정 기간 조회",
            description = "인증된 Creator 본인의 일정 중 from~to와 겹치는 일정을 시작 시각 오름차순으로 조회합니다. 최대 조회 기간은 1년입니다."
    )
    @GetMapping("/api/creator/calendar/schedules")
    public ApiResponse<List<CreatorScheduleResponse.Detail>> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        List<CreatorScheduleResponse.Detail> items = scheduleService.findMine(memberId, from, to).stream()
                .map(CreatorScheduleResponse.Detail::from)
                .toList();
        return ApiResponse.success(items);
    }

    private CreatorScheduleFields toFields(CreatorScheduleRequest.Create request) {
        return new CreatorScheduleFields(
                request.scheduleType(), request.title(), request.description(), request.startAt(), request.endAt(),
                request.timeZone(), request.location(), request.imageUrl(), request.externalUrl());
    }

    private CreatorScheduleFields toFields(CreatorScheduleRequest.Update request) {
        return new CreatorScheduleFields(
                request.scheduleType(), request.title(), request.description(), request.startAt(), request.endAt(),
                request.timeZone(), request.location(), request.imageUrl(), request.externalUrl());
    }
}

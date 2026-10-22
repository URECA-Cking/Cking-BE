package kr.co.cking.calendar.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.calendar.application.MemberCalendarEntryService;
import kr.co.cking.calendar.application.MemberCalendarQueryService;
import kr.co.cking.calendar.application.MemberCalendarScheduleResult;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 인증된 사용자의 개인 캘린더 담기·제거·조회 HTTP 요청을 처리한다(이슈 #319). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "개인 캘린더", description = "인증된 사용자가 여러 크리에이터의 일정을 개인 캘린더에 담아 조회하는 API를 제공합니다.")
public class MemberCalendarController {

    private final MemberCalendarEntryService entryService;
    private final MemberCalendarQueryService queryService;

    @Operation(
            summary = "일정 담기",
            description = "인증된 사용자가 크리에이터 일정을 개인 캘린더에 담습니다. 이미 담긴 일정을 다시 담아도 성공으로 처리합니다(멱등)."
    )
    @PutMapping("/api/me/calendar/schedules/{scheduleId}")
    public ApiResponse<Void> add(@CurrentMemberId Long memberId, @PathVariable @Positive Long scheduleId) {
        entryService.add(memberId, scheduleId);
        return ApiResponse.success();
    }

    @Operation(
            summary = "일정 제거",
            description = "인증된 사용자가 개인 캘린더에서 일정을 제거합니다. 담겨 있지 않은 일정을 제거해도 성공으로 처리합니다(멱등)."
    )
    @DeleteMapping("/api/me/calendar/schedules/{scheduleId}")
    public ResponseEntity<Void> remove(@CurrentMemberId Long memberId, @PathVariable @Positive Long scheduleId) {
        entryService.remove(memberId, scheduleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "내 개인 캘린더 기간 조회",
            description = "인증된 사용자가 담은 일정 중 from~to와 겹치는 일정을 크리에이터 이름과 함께 시작 시각 오름차순으로 조회합니다. 최대 조회 기간은 1년입니다."
    )
    @GetMapping("/api/me/calendar/schedules")
    public ApiResponse<List<MemberCalendarScheduleResult>> findMine(
            @CurrentMemberId Long memberId,
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        return ApiResponse.success(queryService.findMine(memberId, from, to));
    }
}

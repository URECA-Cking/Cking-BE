package kr.co.cking.calendar.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.calendar.application.CreatorScheduleQueryService;
import kr.co.cking.calendar.presentation.dto.CreatorScheduleResponse;
import kr.co.cking.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 크리에이터 캘린더 공개 조회 HTTP 요청을 처리한다(이슈 #293). 인증이 필요 없다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "크리에이터 캘린더 공개 조회", description = "인증 없이 크리에이터 캘린더 일정을 조회하는 API를 제공합니다.")
public class PublicCreatorScheduleController {

    private final CreatorScheduleQueryService queryService;

    @Operation(
            summary = "크리에이터 캘린더 기간 조회",
            description = "인증 없이 조회할 수 있습니다. from~to와 겹치는 일정을 시작 시각 오름차순으로 반환합니다. 최대 조회 기간은 1년입니다."
    )
    @GetMapping("/api/creators/{creatorId}/calendar/schedules")
    public ApiResponse<List<CreatorScheduleResponse.Detail>> findByCreatorId(
            @PathVariable @Positive Long creatorId,
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        List<CreatorScheduleResponse.Detail> items = queryService.findByCreatorId(creatorId, from, to).stream()
                .map(CreatorScheduleResponse.Detail::from)
                .toList();
        return ApiResponse.success(items);
    }

    @Operation(summary = "크리에이터 일정 상세 조회", description = "인증 없이 조회할 수 있습니다.")
    @GetMapping("/api/creators/{creatorId}/calendar/schedules/{scheduleId}")
    public ApiResponse<CreatorScheduleResponse.Detail> find(
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long scheduleId
    ) {
        return ApiResponse.success(CreatorScheduleResponse.Detail.from(queryService.findDetail(creatorId, scheduleId)));
    }
}

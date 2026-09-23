package kr.co.cking.mission.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.mission.application.MissionQueryService;
import kr.co.cking.mission.application.dto.MissionQueryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Mission", description = "Creator별 활성 미션과 선택 사용자의 완료 여부를 조회합니다.")
public class MissionQueryController {

    private final MissionQueryService missionQueryService;

    /** 인증된 사용자의 Creator별 활성 미션과 완료 여부를 조회한다. */
    @Operation(
            summary = "Creator별 미션 목록 조회",
            description = "Creator의 활성 출석·좋아요 미션과 인증된 사용자 기준 UTC periodKey의 오늘 완료 여부를 조회합니다."
    )
    @GetMapping("/api/creators/{creatorId}/missions")
    public ApiResponse<List<MissionQueryItem>> findMissions(
            @PathVariable Long creatorId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(missionQueryService.findMissions(creatorId, memberId));
    }
}

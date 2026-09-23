package kr.co.cking.mission.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.mission.application.CommonMissionQueryService;
import kr.co.cking.mission.application.dto.CommonMissionQueryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공용 미션(크리에이터 무관) 목록 조회 API(이슈 #219). {@code MissionQueryController}와
 * 동일 계약이지만 경로에 creatorId가 없다.
 */
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Mission", description = "Creator별 활성 미션과 선택 사용자의 완료 여부를 조회합니다.")
public class CommonMissionQueryController {

    private final CommonMissionQueryService commonMissionQueryService;

    /** 인증된 사용자의 공용 미션과 완료 여부를 조회한다. */
    @Operation(
            summary = "공용 미션 목록 조회",
            description = "크리에이터에 묶이지 않는 공용 미션과, userId 기준 UTC periodKey의 오늘 완료 여부를 조회합니다."
    )
    @GetMapping("/api/missions")
    public ApiResponse<List<CommonMissionQueryItem>> findMissions(@CurrentMemberId Long memberId) {
        return ApiResponse.success(commonMissionQueryService.findMissions(memberId));
    }
}

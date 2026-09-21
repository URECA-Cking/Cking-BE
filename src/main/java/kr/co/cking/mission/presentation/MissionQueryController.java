package kr.co.cking.mission.presentation;

import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.mission.application.MissionQueryService;
import kr.co.cking.mission.application.dto.MissionQueryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
public class MissionQueryController {

    private final MissionQueryService missionQueryService;

    @GetMapping("/api/creators/{creatorId}/missions")
    public ApiResponse<List<MissionQueryItem>> findMissions(
            @PathVariable Long creatorId,
            @RequestParam @Positive Long userId
    ) {
        return ApiResponse.success(missionQueryService.findMissions(creatorId, userId));
    }
}

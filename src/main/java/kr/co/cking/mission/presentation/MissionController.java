package kr.co.cking.mission.presentation;

import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.presentation.dto.MissionCompleteRequest;
import kr.co.cking.mission.presentation.dto.MissionCompleteResponse;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미션 완료 API. 출석·좋아요 공통 골격(개별 API는 두지 않음, API 명세 v2.5 §4.1).
 */
@RestController
@RequiredArgsConstructor
public class MissionController {

    private final MissionCompletionService missionCompletionService;

    @PostMapping("/api/creators/{creatorId}/missions/{missionId}/complete")
    public ResponseEntity<ApiResponse<MissionCompleteResponse>> complete(
            @PathVariable Long creatorId,
            @PathVariable Long missionId,
            @Valid @RequestBody MissionCompleteRequest request
    ) {
        MissionCompleteCommand command = new MissionCompleteCommand(request.userId(), request.requestId());
        MissionCompleteOutcome outcome = missionCompletionService.complete(creatorId, missionId, command);

        HttpStatus status = outcome.code() == EarnResultCode.EARN_ACCEPTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status)
                .body(ApiResponse.of(outcome.code().name(), MissionCompleteResponse.from(outcome)));
    }
}

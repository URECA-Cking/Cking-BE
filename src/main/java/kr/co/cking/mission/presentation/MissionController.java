package kr.co.cking.mission.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.security.CurrentMemberId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미션 완료 API. 출석·좋아요 공통 골격(개별 API는 두지 않음, API 명세 v2.5 §4.1).
 */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Mission", description = "미션 조회와 완료 처리 API")
public class MissionController {

    private final MissionCompletionService missionCompletionService;

    @PostMapping("/api/creators/{creatorId}/missions/{missionId}/complete")
    @Operation(summary = "Creator 미션 완료", description = "인증된 사용자의 미션 완료를 requestId로 멱등 처리합니다.")
    /** 인증된 사용자의 Creator 미션 완료 요청을 처리한다. */
    public ResponseEntity<ApiResponse<MissionCompleteResponse>> complete(
            @PathVariable @Positive Long creatorId,
            @PathVariable @Positive Long missionId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody MissionCompleteRequest request
    ) {
        MissionCompleteCommand command = new MissionCompleteCommand(memberId, request.requestId());
        MissionCompleteOutcome outcome = missionCompletionService.complete(creatorId, missionId, command);

        HttpStatus status = outcome.code() == EarnResultCode.EARN_ACCEPTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status)
                .body(ApiResponse.of(outcome.code().name(), MissionCompleteResponse.from(outcome)));
    }
}

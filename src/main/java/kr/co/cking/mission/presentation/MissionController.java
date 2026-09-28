package kr.co.cking.mission.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.security.CurrentMemberId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.mission.application.CreatorSpaceShareMissionCompletionService;
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
 * Creator별 좋아요 미션 완료 API. 공용 출석은 {@link CommonMissionController}의
 * 크리에이터 무관 경로를 사용한다.
 */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Mission", description = "미션 조회와 완료 처리 API")
public class MissionController {

    private final MissionCompletionService missionCompletionService;
    private final CreatorSpaceShareMissionCompletionService creatorSpaceShareMissionCompletionService;

    /** 인증된 사용자의 Creator 미션 완료 요청을 처리한다. */
    @PostMapping("/api/creators/{creatorId}/missions/{missionId}/complete")
    @Operation(
            summary = "Creator 좋아요 미션 완료",
            description = "인증된 사용자의 LIKE 미션 완료를 requestId로 멱등 처리합니다. SHARE는 Creator Space 공유 완료 처리에서만 보상하며, 구독 미션은 별도 인증 API를 사용합니다."
    )
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

    /** 인증된 사용자의 Creator Space 공유 완료를 SHARE 미션 보상으로 처리한다. */
    @PostMapping("/api/creators/{creatorId}/missions/share/complete")
    @Operation(
            summary = "Creator Space 공유 미션 완료",
            description = "인증된 사용자의 Creator Space 공유를 requestId로 멱등 처리합니다. 서버 UTC 날짜를 기준으로 Creator별 하루 한 번만 전용 응모권을 적립합니다."
    )
    public ResponseEntity<ApiResponse<MissionCompleteResponse>> completeShare(
            @PathVariable @Positive Long creatorId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody MissionCompleteRequest request
    ) {
        MissionCompleteCommand command = new MissionCompleteCommand(memberId, request.requestId());
        MissionCompleteOutcome outcome = creatorSpaceShareMissionCompletionService.complete(creatorId, command);

        HttpStatus status = outcome.code() == EarnResultCode.EARN_ACCEPTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status)
                .body(ApiResponse.of(outcome.code().name(), MissionCompleteResponse.from(outcome)));
    }
}

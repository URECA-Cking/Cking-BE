package kr.co.cking.mission.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.mission.application.CommonMissionCompletionService;
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
 * 공용 미션(크리에이터 무관) 완료 API(이슈 #219). {@link MissionController}와 동일
 * 계약이지만 경로에 creatorId가 없다.
 */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Mission", description = "Creator별 활성 미션과 선택 사용자의 완료 여부를 조회합니다.")
public class CommonMissionController {

    private final CommonMissionCompletionService commonMissionCompletionService;

    /** 인증된 사용자의 공용 미션 완료 요청을 처리한다. */
    @Operation(
            summary = "공용 미션 완료",
            description = "크리에이터에 묶이지 않는 공용 미션을 완료 처리합니다. requestId로 재시도해도 재적립되지 않습니다."
    )
    @PostMapping("/api/missions/{missionId}/complete")
    public ResponseEntity<ApiResponse<MissionCompleteResponse>> complete(
            @PathVariable @Positive Long missionId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody MissionCompleteRequest request
    ) {
        MissionCompleteCommand command = new MissionCompleteCommand(memberId, request.requestId());
        MissionCompleteOutcome outcome = commonMissionCompletionService.complete(missionId, command);

        HttpStatus status = outcome.code() == EarnResultCode.EARN_ACCEPTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status)
                .body(ApiResponse.of(outcome.code().name(), MissionCompleteResponse.from(outcome)));
    }
}

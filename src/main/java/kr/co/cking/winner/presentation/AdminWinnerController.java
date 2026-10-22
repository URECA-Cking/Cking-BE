package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.winner.application.AdminWinnerDisqualifyService;
import kr.co.cking.winner.application.AdminWinnerReceiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 Winner의 수령 완료와 자격 박탈 상태를 처리하는 HTTP 요청을 담당한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Winner 관리자", description = "관리자의 Winner 수령 완료와 자격 박탈 처리를 제공합니다.")
public class AdminWinnerController {

    private final AdminWinnerReceiveService adminWinnerReceiveService;
    private final AdminWinnerDisqualifyService adminWinnerDisqualifyService;

    /** 관리자가 SELECTED Winner를 RECEIVED 종결 상태로 변경하고 성공 응답을 반환한다. */
    @Operation(
            summary = "Winner 수령 완료",
            description = "인증된 관리자만 SELECTED Winner를 RECEIVED로 변경합니다. "
                    + "상태 변경 이력은 변경 주체·시각과 함께 저장되며, 같은 Winner의 동시 변경은 직렬화됩니다."
    )
    @PostMapping("/api/admin/winners/{winnerId}/receive")
    public ApiResponse<Void> receive(
            @PathVariable @Positive Long winnerId,
            @CurrentMemberId Long memberId
    ) {
        adminWinnerReceiveService.receive(winnerId, memberId);
        return ApiResponse.success();
    }

    /** 관리자가 SELECTED Winner를 DISQUALIFIED 종결 상태로 변경하고 성공 응답을 반환한다. */
    @Operation(
            summary = "Winner 자격 박탈",
            description = "인증된 관리자가 필수 자격 박탈 사유와 함께 SELECTED Winner를 DISQUALIFIED로 변경합니다. "
                    + "사유·변경 주체·시각은 이력에 저장되고, 같은 Winner의 동시 변경은 직렬화됩니다."
    )
    @PostMapping("/api/admin/winners/{winnerId}/disqualify")
    public ApiResponse<Void> disqualify(
            @PathVariable @Positive Long winnerId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody AdminWinnerDisqualifyRequest request
    ) {
        adminWinnerDisqualifyService.disqualify(winnerId, memberId, request.reason());
        return ApiResponse.success();
    }
}

package kr.co.cking.snapshot.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.snapshot.application.SnapshotQueryResult;
import kr.co.cking.snapshot.application.SnapshotQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Snapshot 관리자", description = "관리자의 공식 추첨 Snapshot 조회 API를 제공합니다.")
public class SnapshotAdminController {

    private final SnapshotQueryService snapshotQueryService;

    /** 관리자가 CLOSED Event의 공식 Snapshot과 후보·경품 확정 정보를 조회한다. */
    @Operation(
            summary = "공식 Snapshot 조회",
            description = "관리자만 CLOSED Event에 확정된 추첨 후보·경품 Snapshot과 무결성 정보를 조회합니다."
    )
    @GetMapping("/api/admin/events/{eventId}/snapshot")
    public ApiResponse<SnapshotQueryResult> getOfficialSnapshot(
            @PathVariable @Positive Long eventId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(snapshotQueryService.getOfficialSnapshot(eventId, memberId));
    }
}

package kr.co.cking.snapshot.presentation;

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
public class SnapshotAdminController {

    private final SnapshotQueryService snapshotQueryService;

    @GetMapping("/api/admin/events/{eventId}/snapshot")
    public ApiResponse<SnapshotQueryResult> getOfficialSnapshot(
            @PathVariable @Positive Long eventId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(snapshotQueryService.getOfficialSnapshot(eventId, memberId));
    }
}

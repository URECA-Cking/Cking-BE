package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorActivityNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Creator 본인의 공개 활동명을 별도 관리한다. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Creator Profile", description = "Creator 활동명 조회·수정 API를 제공합니다.")
public class CreatorProfileController {

    private final CreatorActivityNameService activityNameService;

    @Operation(summary = "내 Creator 활동명 조회", description = "Creator 본인의 공개 활동명과 변경 가능 시각을 조회합니다.")
    @GetMapping("/api/creator/profile")
    public ApiResponse<CreatorActivityNameService.ActivityNameProfile> findMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(activityNameService.findMine(memberId));
    }

    @Operation(summary = "내 Creator 활동명 수정", description = "활동명을 전체 교체합니다. 최초 설정 후 실제 변경은 14일 간격이며, 이전 활동명 복귀는 예약 기간 안에 즉시 가능합니다.")
    @PutMapping("/api/creator/profile")
    public ApiResponse<CreatorActivityNameService.ActivityNameProfile> updateMine(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorActivityNameRequest request
    ) {
        return ApiResponse.success(activityNameService.updateMine(memberId, request.activityName()));
    }

    public record CreatorActivityNameRequest(@NotBlank @Size(max = 20) String activityName) {
    }
}

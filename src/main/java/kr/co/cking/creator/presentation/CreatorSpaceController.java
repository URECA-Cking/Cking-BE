package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorSpaceProfileService;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.presentation.dto.CreatorSpaceRequest;
import kr.co.cking.creator.presentation.dto.CreatorSpaceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Creator Space 홈·프로필 공개 조회와 Creator 본인의 조회·수정 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Creator Space", description = "Creator Space 홈·프로필 조회·수정 API를 제공합니다.")
public class CreatorSpaceController {

    private final CreatorSpaceProfileService profileService;

    /** Creator ID로 공개 Creator Space를 조회한다. */
    @Operation(
            summary = "Creator Space 조회",
            description = "인증 없이 조회할 수 있습니다. 응답의 slug로 공유 URL을 만들 수 있습니다."
    )
    @GetMapping("/api/creators/{creatorId}/space")
    public ApiResponse<CreatorSpaceResponse.Detail> findByCreatorId(@PathVariable Long creatorId) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.findByCreatorId(creatorId)));
    }

    /** 공유 URL slug로 공개 Creator Space를 조회한다. */
    @Operation(
            summary = "공유 URL로 Creator Space 조회",
            description = "인증 없이 조회할 수 있습니다. slug는 공유 URL 탐색에만 사용하며 응답의 creatorId를 내부 식별자로 사용합니다."
    )
    @GetMapping("/api/creator-spaces/{slug}")
    public ApiResponse<CreatorSpaceResponse.Detail> findBySlug(@PathVariable String slug) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.findBySlug(slug)));
    }

    /** 인증된 Creator 본인의 Creator Space를 조회한다. */
    @Operation(summary = "내 Creator Space 조회", description = "Creator 본인만 조회할 수 있습니다.")
    @GetMapping("/api/creator/space")
    public ApiResponse<CreatorSpaceResponse.Detail> findMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.findMine(memberId)));
    }

    /** 인증된 Creator 본인의 Creator Space 프로필을 갱신한다. */
    @Operation(
            summary = "내 Creator Space 홈·프로필 수정",
            description = "Creator 본인만 수정할 수 있습니다. 모든 필드를 한 번에 교체하며 slug는 수정할 수 없습니다. "
                    + "탭 노출 여부는 화면 노출만 제어합니다."
    )
    @PatchMapping("/api/creator/space")
    public ApiResponse<CreatorSpaceResponse.Detail> updateMine(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceRequest.UpdateProfile request
    ) {
        CreatorSpaceProfileFields fields = new CreatorSpaceProfileFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl(),
                request.homeTabEnabled(), request.missionsTabEnabled(), request.postsTabEnabled(), request.eventsTabEnabled()
        );
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.updateMine(memberId, fields)));
    }
}

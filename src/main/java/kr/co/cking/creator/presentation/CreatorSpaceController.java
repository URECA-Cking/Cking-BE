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
@Tag(name = "Creator Space", description = "Creator Space 홈·프로필 조회·수정과 커스텀 slug 변경 API를 제공합니다.")
public class CreatorSpaceController {

    private final CreatorSpaceProfileService profileService;

    @Operation(
            summary = "Creator Space 조회",
            description = "인증 없이 조회할 수 있습니다. 응답의 slug로 공유 URL을 만들 수 있습니다."
    )
    @GetMapping("/api/creators/{creatorId}/space")
    public ApiResponse<CreatorSpaceResponse.Detail> findByCreatorId(@PathVariable Long creatorId) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.findByCreatorId(creatorId)));
    }

    @Operation(
            summary = "slug로 Creator Space 조회",
            description = "인증 없이 조회할 수 있습니다. 공유 링크(/space/{slug})에서 사용합니다."
    )
    @GetMapping("/api/creator-spaces/{slug}")
    public ApiResponse<CreatorSpaceResponse.Detail> findBySlug(@PathVariable String slug) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.findBySlug(slug)));
    }

    @Operation(summary = "내 Creator Space 조회", description = "Creator 본인만 조회할 수 있습니다.")
    @GetMapping("/api/creator/space")
    public ApiResponse<CreatorSpaceResponse.Detail> findMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.findMine(memberId)));
    }

    @Operation(
            summary = "내 Creator Space 홈·프로필 수정",
            description = "Creator 본인만 수정할 수 있습니다. 모든 필드를 한 번에 교체합니다. slug는 slug 변경 API로 바꿉니다."
    )
    @PatchMapping("/api/creator/space")
    public ApiResponse<CreatorSpaceResponse.Detail> updateMine(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceRequest.UpdateProfile request
    ) {
        CreatorSpaceProfileFields fields = new CreatorSpaceProfileFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl());
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.updateMine(memberId, fields)));
    }

    @Operation(
            summary = "내 Creator Space slug 변경",
            description = "Creator 본인만 변경할 수 있습니다. 3~30자 소문자·숫자·하이픈·밑줄이며 예약어와 다른 Space가 쓰는 "
                    + "slug는 사용할 수 없습니다. 마지막 변경 후 14일이 지나야 다시 바꿀 수 있으며(첫 변경은 바로 가능), "
                    + "바꾸면 이전 slug 링크는 더 이상 열리지 않습니다."
    )
    @PatchMapping("/api/creator/space/slug")
    public ApiResponse<CreatorSpaceResponse.Detail> changeSlug(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceRequest.ChangeSlug request
    ) {
        return ApiResponse.success(CreatorSpaceResponse.Detail.from(profileService.changeSlug(memberId, request.slug())));
    }
}

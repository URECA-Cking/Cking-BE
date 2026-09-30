package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorSpaceProfileService;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.presentation.dto.CreatorSpaceRequest;
import kr.co.cking.creator.presentation.dto.CreatorSpaceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Creator Space 홈·프로필 공개 조회와 Creator 본인의 조회·수정 HTTP 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space", description = "Creator Space 홈·프로필 조회·수정과 커스텀 slug 변경 API를 제공합니다.")
public class CreatorSpaceController {

    private final CreatorSpaceProfileService profileService;

    /** 공개 Creator Space가 있는 Creator 목록을 페이지로 조회한다. */
    @Operation(
            summary = "공개 Creator 목록 조회",
            description = "인증 없이 조회할 수 있습니다. Creator 이름 오름차순(같으면 creatorId 오름차순)이며, "
                    + "keyword가 있으면 이름에 포함된 Creator만 반환합니다."
    )
    @GetMapping("/api/creators")
    public ApiResponse<PageResponse<CreatorSpaceResponse.Detail>> findAll(
            @RequestParam(required = false) @Size(max = 50) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(PageResponse.from(
                profileService.findPublicSpaces(keyword, PageRequest.of(page, size))
                        .map(CreatorSpaceResponse.Detail::from)));
    }

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
    public ApiResponse<CreatorSpaceResponse.Mine> findMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(CreatorSpaceResponse.Mine.from(profileService.findMine(memberId)));
    }

    /** 인증된 Creator 본인의 Creator Space 프로필을 갱신한다. */
    /** 인증된 Creator 본인의 Creator Space slug를 변경한다. */
    @Operation(
            summary = "내 Creator Space 홈·프로필 수정",
            description = "Creator 본인만 수정할 수 있습니다. 모든 필드를 한 번에 교체합니다. slug는 slug 변경 API로 바꿉니다."
    )
    @PatchMapping("/api/creator/space")
    public ApiResponse<CreatorSpaceResponse.Mine> updateMine(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceRequest.UpdateProfile request
    ) {
        CreatorSpaceProfileFields fields = new CreatorSpaceProfileFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl());
        return ApiResponse.success(CreatorSpaceResponse.Mine.from(profileService.updateMine(memberId, fields)));
    }

    @Operation(
            summary = "내 Creator Space slug 변경",
            description = "Creator 본인만 변경할 수 있습니다. 3~30자 소문자·숫자·하이픈·밑줄이며 예약어와 다른 Space가 쓰는 "
                    + "slug는 사용할 수 없습니다. 마지막 변경 후 14일이 지나야 다시 바꿀 수 있으며(첫 변경은 바로 가능), "
                    + "바꾸면 이전 slug 링크는 더 이상 열리지 않습니다. 이전 slug는 14일간 예약돼 다른 Creator가 쓸 수 없고, "
                    + "본인은 그 기간 안에 변경 제한 없이 되돌릴 수 있습니다."
    )
    @PatchMapping("/api/creator/space/slug")
    public ApiResponse<CreatorSpaceResponse.Mine> changeSlug(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceRequest.ChangeSlug request
    ) {
        return ApiResponse.success(CreatorSpaceResponse.Mine.from(profileService.changeSlug(memberId, request.slug())));
    }
}

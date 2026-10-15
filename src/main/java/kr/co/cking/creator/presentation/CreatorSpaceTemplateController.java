package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageProcessingLimiter;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorSpaceTemplateImageUploadService;
import kr.co.cking.creator.application.CreatorSpaceTemplateService;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.presentation.dto.CreatorApplicationResponse;
import kr.co.cking.creator.presentation.dto.CreatorSpaceTemplateImageResponse;
import kr.co.cking.creator.presentation.dto.CreatorSpaceTemplateRequest;
import kr.co.cking.creator.presentation.dto.CreatorSpaceTemplateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** 관리자의 기본 크리에이터 스페이스 템플릿 생성·조회·수정·활성화 HTTP 요청을 처리한다. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping
@Tag(name = "Creator Space Template 관리자", description = "관리자의 기본 크리에이터 스페이스 템플릿 관리 API를 제공합니다.")
public class CreatorSpaceTemplateController {

    private final CreatorSpaceTemplateService templateService;
    private final CreatorSpaceTemplateImageUploadService templateImageUploadService;
    private final ImageProcessingLimiter imageProcessingLimiter;

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 생성",
            description = "관리자만 생성할 수 있습니다. 생성 직후 템플릿은 비활성 상태입니다."
    )
    @PostMapping("/api/admin/creator-space-templates")
    public ResponseEntity<ApiResponse<CreatorSpaceTemplateResponse.Detail>> create(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceTemplateRequest.Create request
    ) {
        CreatorSpaceTemplate template = templateService.create(memberId, new CreatorSpaceTemplateFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl(), request.slugRule()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(CreatorSpaceTemplateResponse.Detail.from(template)));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 프로필 이미지 업로드",
            description = "관리자만 올릴 수 있습니다. 이미지를 올리고 템플릿의 profileImageUrl에 넣을 CDN imageUrl을 반환합니다. "
                    + "템플릿에 연결되지는 않으며, 올린 이미지는 정리 작업으로 삭제되지 않습니다."
    )
    @PostMapping(value = "/api/admin/creator-space-templates/profile-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CreatorSpaceTemplateImageResponse>> uploadProfileImage(
            @CurrentMemberId Long memberId, @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String imageUrl = imageProcessingLimiter.admit(() -> templateImageUploadService.uploadProfile(memberId, readBytes(image)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new CreatorSpaceTemplateImageResponse(imageUrl)));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 배너 이미지 업로드",
            description = "관리자만 올릴 수 있습니다. 이미지를 올리고 템플릿의 bannerImageUrl에 넣을 CDN imageUrl을 반환합니다. "
                    + "템플릿에 연결되지는 않으며, 올린 이미지는 정리 작업으로 삭제되지 않습니다."
    )
    @PostMapping(value = "/api/admin/creator-space-templates/banner-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CreatorSpaceTemplateImageResponse>> uploadBannerImage(
            @CurrentMemberId Long memberId, @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String imageUrl = imageProcessingLimiter.admit(() -> templateImageUploadService.uploadBanner(memberId, readBytes(image)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new CreatorSpaceTemplateImageResponse(imageUrl)));
    }

    private byte[] readBytes(MultipartFile image) {
        if (image == null) throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        try { return image.getBytes(); }
        catch (IOException exception) { throw new BusinessException(CommonErrorCode.SYSTEM_ERROR); }
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 목록 조회",
            description = "관리자만 조회할 수 있습니다. 기본 정렬은 생성일 내림차순입니다."
    )
    @GetMapping("/api/admin/creator-space-templates")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorSpaceTemplateResponse.Detail>> findAll(
            @CurrentMemberId Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorSpaceTemplate> templates = templateService.findAllForAdmin(memberId, PageRequest.of(page, size));
        List<CreatorSpaceTemplateResponse.Detail> items = templates.stream()
                .map(CreatorSpaceTemplateResponse.Detail::from)
                .toList();
        return ApiResponse.success(CreatorApplicationResponse.PageResult.from(templates, items));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 상세 조회",
            description = "관리자만 조회할 수 있습니다."
    )
    @GetMapping("/api/admin/creator-space-templates/{templateId}")
    public ApiResponse<CreatorSpaceTemplateResponse.Detail> find(
            @PathVariable Long templateId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(
                CreatorSpaceTemplateResponse.Detail.from(templateService.findForAdmin(memberId, templateId)));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 수정",
            description = "관리자만 수정할 수 있습니다. 전체 필드를 새 값으로 교체하며, 이미 생성된 Creator 스페이스에는 영향을 주지 않습니다."
    )
    @PatchMapping("/api/admin/creator-space-templates/{templateId}")
    public ApiResponse<CreatorSpaceTemplateResponse.Detail> update(
            @PathVariable Long templateId,
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorSpaceTemplateRequest.Update request
    ) {
        CreatorSpaceTemplate template = templateService.update(memberId, templateId, new CreatorSpaceTemplateFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl(), request.slugRule()));
        return ApiResponse.success(CreatorSpaceTemplateResponse.Detail.from(template));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 활성화",
            description = "관리자만 활성화할 수 있습니다. 활성 템플릿은 항상 하나뿐이며, 기존 활성 템플릿은 자동으로 비활성화됩니다. "
                    + "동시 활성화 요청은 직렬화됩니다."
    )
    @PostMapping("/api/admin/creator-space-templates/{templateId}/activate")
    public ApiResponse<CreatorSpaceTemplateResponse.Detail> activate(
            @PathVariable Long templateId,
            @CurrentMemberId Long memberId
    ) {
        return ApiResponse.success(
                CreatorSpaceTemplateResponse.Detail.from(templateService.activate(memberId, templateId)));
    }
}

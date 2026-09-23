package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.creator.application.CreatorSpaceTemplateService;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.presentation.dto.CreatorApplicationResponse;
import kr.co.cking.creator.presentation.dto.CreatorSpaceTemplateRequest;
import kr.co.cking.creator.presentation.dto.CreatorSpaceTemplateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 관리자의 기본 크리에이터 스페이스 템플릿 생성·조회·수정·활성화 HTTP 요청을 처리한다. */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping
@Tag(name = "Creator Space Template 관리자", description = "관리자의 기본 크리에이터 스페이스 템플릿 관리 API를 제공합니다.")
public class CreatorSpaceTemplateController {

    private final CreatorSpaceTemplateService templateService;

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 생성",
            description = "관리자만 생성할 수 있습니다. 생성 직후 템플릿은 비활성 상태입니다."
    )
    @PostMapping("/api/admin/creator-space-templates")
    public ResponseEntity<ApiResponse<CreatorSpaceTemplateResponse.Detail>> create(
            @Valid @RequestBody CreatorSpaceTemplateRequest.Create request
    ) {
        CreatorSpaceTemplate template = templateService.create(request.userId(), toFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl(), request.slugRule(),
                request.homeTabEnabled(), request.missionsTabEnabled(), request.postsTabEnabled(), request.eventsTabEnabled()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(CreatorSpaceTemplateResponse.Detail.from(template)));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 목록 조회",
            description = "관리자만 조회할 수 있습니다. 기본 정렬은 생성일 내림차순입니다."
    )
    @GetMapping("/api/admin/creator-space-templates")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorSpaceTemplateResponse.Detail>> findAll(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorSpaceTemplate> templates = templateService.findAllForAdmin(userId, PageRequest.of(page, size));
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
            @RequestParam Long userId
    ) {
        return ApiResponse.success(
                CreatorSpaceTemplateResponse.Detail.from(templateService.findForAdmin(userId, templateId)));
    }

    @Operation(
            summary = "기본 크리에이터 스페이스 템플릿 수정",
            description = "관리자만 수정할 수 있습니다. 전체 필드를 새 값으로 교체하며, 이미 생성된 Creator 스페이스에는 영향을 주지 않습니다."
    )
    @PatchMapping("/api/admin/creator-space-templates/{templateId}")
    public ApiResponse<CreatorSpaceTemplateResponse.Detail> update(
            @PathVariable Long templateId,
            @Valid @RequestBody CreatorSpaceTemplateRequest.Update request
    ) {
        CreatorSpaceTemplate template = templateService.update(request.userId(), templateId, toFields(
                request.introText(), request.profileImageUrl(), request.bannerImageUrl(), request.slugRule(),
                request.homeTabEnabled(), request.missionsTabEnabled(), request.postsTabEnabled(), request.eventsTabEnabled()));
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
            @Valid @RequestBody CreatorSpaceTemplateRequest.Activate request
    ) {
        return ApiResponse.success(
                CreatorSpaceTemplateResponse.Detail.from(templateService.activate(request.userId(), templateId)));
    }

    private CreatorSpaceTemplateFields toFields(
            String introText, String profileImageUrl, String bannerImageUrl, String slugRule,
            boolean homeTabEnabled, boolean missionsTabEnabled, boolean postsTabEnabled, boolean eventsTabEnabled
    ) {
        return new CreatorSpaceTemplateFields(
                introText, profileImageUrl, bannerImageUrl, slugRule, homeTabEnabled, missionsTabEnabled,
                postsTabEnabled, eventsTabEnabled);
    }
}

package kr.co.cking.upload.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.upload.application.ImageUploadService;
import kr.co.cking.upload.presentation.dto.ImageUploadCreateRequest;
import kr.co.cking.upload.presentation.dto.ImageUploadCreateResponse;
import kr.co.cking.upload.presentation.dto.ImageUploadStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 결과 큐 주소가 없는 환경에서는 등록되지 않아 404다. 화면은 404면 기존 업로드 API를 쓴다. */
@Tag(name = "Image Upload", description = "앱 밖 파이프라인 이미지 업로드")
@RestController
@RequiredArgsConstructor
@ConditionalOnExpression("!'${cking.image-pipeline.results-queue-url:}'.isEmpty()")
public class ImageUploadController {

    private final ImageUploadService service;

    @Operation(summary = "이미지 업로드 예약", description = "업로드 기록을 만들고 5분 동안 쓸 수 있는 사전 서명 PUT 주소를 반환합니다. "
            + "headers를 그대로 붙여 원본을 PUT한 뒤 상태 조회로 imageKey를 받습니다.")
    @PostMapping("/api/uploads")
    public ResponseEntity<ApiResponse<ImageUploadCreateResponse>> create(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody ImageUploadCreateRequest request
    ) {
        ImageUploadService.CreatedUpload created =
                service.create(memberId, request.purpose(), request.contentType(), request.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new ImageUploadCreateResponse(
                created.uploadId(), created.presigned().url().toString(), created.presigned().headers(), created.expiresAt())));
    }

    @Operation(summary = "이미지 업로드 상태", description = "PENDING, READY(imageKey 포함), FAILED(failureReason 포함)")
    @GetMapping("/api/uploads/{uploadId}")
    public ApiResponse<ImageUploadStatusResponse> status(@CurrentMemberId Long memberId, @PathVariable String uploadId) {
        return ApiResponse.success(ImageUploadStatusResponse.of(service.get(memberId, uploadId)));
    }
}

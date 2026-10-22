package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageProcessingLimiter;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.CreatorPostService;
import kr.co.cking.post.application.PostImageUploadService;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.presentation.dto.CreatorPostRequest;
import kr.co.cking.post.presentation.dto.CreatorPostResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 인증된 Creator 본인의 게시글 이미지 업로드와 게시글 작성·수정·삭제 HTTP 요청을 처리한다(이슈 #318). */
@RestController
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Creator Space 게시글", description = "인증된 Creator 본인의 게시글 작성·수정·삭제와 이미지 업로드 API를 제공합니다.")
public class CreatorPostController {

    private final PostImageUploadService imageUploadService;
    private final ImageProcessingLimiter imageProcessingLimiter;
    private final CreatorPostService postService;

    @Operation(
            summary = "게시글 이미지 업로드",
            description = "요청 1건당 이미지 1장(JPEG/PNG, 최대 5MB)을 올립니다. 반환된 imageKey를 게시글 작성·수정 요청에 "
                    + "순서대로 넣습니다. 24시간 안에 게시글에 연결되지 않은 이미지는 삭제됩니다."
    )
    @PostMapping(value = "/api/creator/posts/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CreatorPostResponse.UploadedImage>> uploadImage(
            @CurrentMemberId Long memberId,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String imageKey = imageProcessingLimiter.admit(
                () -> imageUploadService.upload(memberId, readBytes(image)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(new CreatorPostResponse.UploadedImage(imageKey)));
    }

    @Operation(
            summary = "게시글 작성",
            description = "공개 범위(PUBLIC/FOLLOWERS)를 정해 게시글을 작성합니다. 본문이나 이미지 중 하나는 있어야 하며, "
                    + "이미지는 최대 5장입니다."
    )
    @PostMapping("/api/creator/posts")
    public ResponseEntity<ApiResponse<CreatorPostResponse.Detail>> create(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorPostRequest.Save request
    ) {
        CreatorPostView post = postService.create(memberId, request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(CreatorPostResponse.Detail.from(post)));
    }

    @Operation(
            summary = "게시글 수정",
            description = "본인 게시글만 수정할 수 있습니다. 부분 수정이 아니며 본문·공개 범위·이미지 목록을 모두 새 값으로 교체합니다. "
                    + "목록에서 빠진 이미지는 삭제됩니다."
    )
    @PatchMapping("/api/creator/posts/{postId}")
    public ApiResponse<CreatorPostResponse.Detail> update(
            @CurrentMemberId Long memberId,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody CreatorPostRequest.Save request
    ) {
        CreatorPostView post = postService.update(memberId, postId, request.toFields());
        return ApiResponse.success(CreatorPostResponse.Detail.from(post));
    }

    @Operation(summary = "게시글 삭제", description = "본인 게시글만 삭제할 수 있습니다. 연결된 이미지도 삭제됩니다.")
    @DeleteMapping("/api/creator/posts/{postId}")
    public ResponseEntity<Void> delete(@CurrentMemberId Long memberId, @PathVariable @Positive Long postId) {
        postService.delete(memberId, postId);
        return ResponseEntity.noContent().build();
    }

    private byte[] readBytes(MultipartFile image) {
        if (image == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return image.getBytes();
        } catch (IOException exception) {
            log.error("게시글 multipart 이미지를 읽지 못했습니다.", exception);
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
    }
}

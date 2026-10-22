package kr.co.cking.member.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageProcessingLimiter;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.CreatorQueryService;
import kr.co.cking.member.application.MemberOnboardingService;
import kr.co.cking.member.application.MemberProfileImageUploadService;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.application.MemberProfileUpdateService;
import kr.co.cking.member.presentation.dto.MemberProfileImageResponse;
import kr.co.cking.member.presentation.dto.MemberProfileRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Member", description = "인증된 현재 사용자 기본 정보를 조회합니다.")
public class MemberController {

    private final MemberQueryService memberQueryService;
    private final CreatorQueryService creatorQueryService;
    private final MemberOnboardingService memberOnboardingService;
    private final MemberProfileImageUploadService profileImageUploadService;
    private final ImageProcessingLimiter imageProcessingLimiter;
    private final MemberProfileUpdateService memberProfileUpdateService;

    /** Access JWT로 식별된 현재 Member의 프로필과 Creator 여부를 반환한다. */
    @Operation(summary = "현재 사용자 조회", description = "Access JWT로 인증된 사용자의 기본 정보와 Creator 여부를 반환합니다.")
    @GetMapping("/api/me")
    public ApiResponse<MyProfileResponse> getMyProfile(@CurrentMemberId Long memberId) {
        var profile = memberQueryService.getProfile(memberId);
        boolean creator = creatorQueryService.isCreatorMember(memberId);
        return ApiResponse.success(MyProfileResponse.from(profile, creator));
    }

    /** 온보딩 완료(또는 건너뛰기)를 기록한다. 여러 번 호출해도 결과가 같다. */
    @Operation(summary = "온보딩 완료 기록", description = "인증된 사용자의 온보딩 완료를 멱등하게 기록합니다.")
    @PutMapping("/api/me/onboarding/complete")
    public ApiResponse<Void> completeOnboarding(@CurrentMemberId Long memberId) {
        memberOnboardingService.complete(memberId);
        return ApiResponse.success();
    }

    @Operation(summary = "내 프로필 이미지 업로드", description = "이미지 한 장을 올리고 프로필 수정 API에 사용할 imageKey를 반환합니다. "
            + "24시간 안에 프로필에 연결하지 않으면 삭제됩니다.")
    @PostMapping(value = "/api/me/profile-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MemberProfileImageResponse>> uploadProfileImage(
            @CurrentMemberId Long memberId,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        String imageKey = imageProcessingLimiter.admit(
                () -> profileImageUploadService.upload(memberId, readBytes(image)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(new MemberProfileImageResponse(imageKey)));
    }

    @Operation(summary = "내 프로필 수정", description = "닉네임을 수정합니다. profileImageKey를 생략하면 현재 사진을 유지하고, "
            + "새 key를 보내면 교체하며, null을 명시하면 기본 이미지로 돌아갑니다.")
    @PutMapping("/api/me/profile")
    public ApiResponse<MyProfileResponse> updateMyProfile(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody MemberProfileRequest request
    ) {
        memberProfileUpdateService.update(memberId, request.toFields());
        var profile = memberQueryService.getProfile(memberId);
        boolean creator = creatorQueryService.isCreatorMember(memberId);
        return ApiResponse.success(MyProfileResponse.from(profile, creator));
    }

    private byte[] readBytes(MultipartFile image) {
        if (image == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return image.getBytes();
        } catch (IOException exception) {
            log.error("프로필 multipart 이미지를 읽지 못했습니다.", exception);
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
    }
}

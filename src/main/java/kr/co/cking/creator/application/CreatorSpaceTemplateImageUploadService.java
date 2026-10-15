package kr.co.cking.creator.application;

import static kr.co.cking.creator.application.CreatorSpaceProfileImageUploadService.BANNER_POLICY;
import static kr.co.cking.creator.application.CreatorSpaceProfileImageUploadService.PROFILE_POLICY;

import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자가 기본 Creator Space 템플릿의 기본 프로필 이미지·배너를 파일로 올린다(이슈 #593).
 * Creator Space 이미지 업로드와 같은 정규화 정책·저장소·CDN 접두사를 쓰지만 Creator 소유 기록은 만들지 않고,
 * 올린 이미지의 CDN URL을 돌려준다. 관리자는 이 URL을 템플릿 생성·수정 API의 URL 필드에 넣어 저장한다.
 *
 * <p>업로드 기록(creator_space_profile_image)을 남기지 않으므로 Creator 이미지 정리 작업이 이 객체를 지우지 않는다.
 * 템플릿 값은 Creator 승인 시점에 Space로 URL이 복사되어 기본 이미지로 쓰이기 때문에, 템플릿을 바꿔도
 * 이전 객체를 삭제하지 않는다. 키는 숫자 creatorId 하위 경로와 겹치지 않도록 {@code template/} 아래에 만든다.
 */
@Service @RequiredArgsConstructor
public class CreatorSpaceTemplateImageUploadService {
    private static final String KEY_PREFIX = "creator-space-profile-images/template/";
    private final MemberRepository memberRepository;
    private final ImageNormalizer imageNormalizer;
    private final ObjectStorage objectStorage;
    private final CreatorSpaceProfileImageUrlResolver imageUrlResolver;

    /** 기본 프로필 이미지를 올리고 CDN URL을 돌려준다. */
    public String uploadProfile(Long adminId, byte[] bytes) {
        return upload(adminId, bytes, PROFILE_POLICY, "profile");
    }

    /** 기본 배너 이미지를 올리고 CDN URL을 돌려준다. */
    public String uploadBanner(Long adminId, byte[] bytes) {
        return upload(adminId, bytes, BANNER_POLICY, "banner");
    }

    private String upload(Long adminId, byte[] bytes, ImagePolicy policy, String kind) {
        requireAdmin(adminId);
        NormalizedImage image = normalize(bytes, policy);
        String key = "%s%s/%s.jpg".formatted(KEY_PREFIX, kind, UUID.randomUUID());
        objectStorage.put(key, image.normalizedImageBytes(), "image/jpeg");
        return imageUrlResolver.resolve(key);
    }

    private void requireAdmin(Long adminId) {
        Member admin = memberRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (admin.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private NormalizedImage normalize(byte[] bytes, ImagePolicy policy) {
        try { return imageNormalizer.normalize(bytes, policy); }
        catch (InvalidImageException exception) {
            throw new BusinessException(CreatorErrorCode.INVALID_SPACE_TEMPLATE_IMAGE);
        }
    }
}

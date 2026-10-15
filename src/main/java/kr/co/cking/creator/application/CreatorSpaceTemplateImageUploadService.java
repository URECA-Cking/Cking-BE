package kr.co.cking.creator.application;

import static kr.co.cking.creator.application.CreatorSpaceProfileImageUploadService.BANNER_POLICY;
import static kr.co.cking.creator.application.CreatorSpaceProfileImageUploadService.PROFILE_POLICY;

import java.time.Clock;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpaceImageKind;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImage;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
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
 * <p>업로드는 정리용 기록({@code creator_space_template_image}, 이슈 #598)을 남긴다. 템플릿에 저장하지 않은 업로드와
 * 더는 쓰이지 않는 이미지를 {@code CreatorSpaceTemplateImageCleanupScheduler}가 URL 참조를 대조해 지운다.
 * 템플릿 값은 Creator 승인 시점에 Space로 URL이 복사되어 기본 이미지로 쓰이므로, 기록만으로 지우지 않는다.
 * 키는 숫자 creatorId 하위 경로와 겹치지 않도록 {@code template/} 아래에 만든다.
 */
@Service @RequiredArgsConstructor
public class CreatorSpaceTemplateImageUploadService {
    private static final String KEY_PREFIX = "creator-space-profile-images/template/";
    private final MemberRepository memberRepository;
    private final CreatorSpaceTemplateImageRepository imageRepository;
    private final ImageNormalizer imageNormalizer;
    private final ObjectStorage objectStorage;
    private final CreatorSpaceProfileImageUrlResolver imageUrlResolver;
    private final Clock clock;

    /** 기본 프로필 이미지를 올리고 CDN URL을 돌려준다. */
    public String uploadProfile(Long adminId, byte[] bytes) {
        return upload(adminId, bytes, PROFILE_POLICY, CreatorSpaceImageKind.PROFILE, "profile");
    }

    /** 기본 배너 이미지를 올리고 CDN URL을 돌려준다. */
    public String uploadBanner(Long adminId, byte[] bytes) {
        return upload(adminId, bytes, BANNER_POLICY, CreatorSpaceImageKind.BANNER, "banner");
    }

    private String upload(Long adminId, byte[] bytes, ImagePolicy policy, CreatorSpaceImageKind kind, String pathSegment) {
        requireAdmin(adminId);
        NormalizedImage image = normalize(bytes, policy);
        String key = "%s%s/%s.jpg".formatted(KEY_PREFIX, pathSegment, UUID.randomUUID());
        // 올리다 실패해도 객체가 남았을 수 있으므로 저장소에 올리기 전에 기록을 남긴다. 정리 작업이 회수한다.
        imageRepository.save(CreatorSpaceTemplateImage.uploading(key, kind, clock.instant()));
        objectStorage.put(key, image.normalizedImageBytes(), "image/jpeg");
        imageRepository.markUploaded(key, clock.instant());
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

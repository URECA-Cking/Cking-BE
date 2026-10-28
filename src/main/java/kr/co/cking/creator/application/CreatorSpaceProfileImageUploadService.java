package kr.co.cking.creator.application;

import java.time.Clock;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpaceProfileImage;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Creator Space 프로필 사진과 배너 이미지(이슈 #590)를 올린다. 두 종류는 같은 저장 경로 접두사
 * ({@code creator-space-profile-images/})와 같은 정리 작업을 쓰고, 정책(최소 해상도)과 하위 경로·오류 코드만 다르다.
 */
@Service @RequiredArgsConstructor
public class CreatorSpaceProfileImageUploadService {
    static final ImagePolicy PROFILE_POLICY = new ImagePolicy(200, 200, 2048);
    /** 배너는 가로로 넓게 쓰이므로 가로를 더 크게 요구한다. 가로세로 비율은 강제하지 않고 화면이 잘라 보여 준다. */
    static final ImagePolicy BANNER_POLICY = new ImagePolicy(600, 200, 2048);
    private static final String KEY_PREFIX = "creator-space-profile-images/";
    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceProfileImageRepository imageRepository;
    private final ImageNormalizer imageNormalizer;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    public String upload(Long memberId, byte[] bytes) {
        Creator creator = requireCreatorWithSpace(memberId);
        NormalizedImage image = normalize(bytes, PROFILE_POLICY, CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);
        String key = "%s%d/%s.jpg".formatted(KEY_PREFIX, creator.getCreatorId(), UUID.randomUUID());
        imageRepository.save(CreatorSpaceProfileImage.uploading(key, creator.getCreatorId(), clock.instant()));
        return store(key, image);
    }

    /** 배너 이미지를 올리고 연결에 쓸 imageKey를 돌려준다. 키는 프로필 사진과 구분되는 {@code banner/} 하위에 만든다. */
    public String uploadBanner(Long memberId, byte[] bytes) {
        Creator creator = requireCreatorWithSpace(memberId);
        NormalizedImage image = normalize(bytes, BANNER_POLICY, CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);
        String key = "%s%d/banner/%s.jpg".formatted(KEY_PREFIX, creator.getCreatorId(), UUID.randomUUID());
        imageRepository.save(CreatorSpaceProfileImage.uploadingBanner(key, creator.getCreatorId(), clock.instant()));
        return store(key, image);
    }

    /** 정규화를 앱 밖 파이프라인에 맡길 때 권한 확인과 업로드 기록만 먼저 만든다(#780). */
    public String reserve(Long memberId) {
        Creator creator = requireCreatorWithSpace(memberId);
        String key = "%s%d/%s.jpg".formatted(KEY_PREFIX, creator.getCreatorId(), UUID.randomUUID());
        imageRepository.save(CreatorSpaceProfileImage.uploading(key, creator.getCreatorId(), clock.instant()));
        return key;
    }

    public String reserveBanner(Long memberId) {
        Creator creator = requireCreatorWithSpace(memberId);
        String key = "%s%d/banner/%s.jpg".formatted(KEY_PREFIX, creator.getCreatorId(), UUID.randomUUID());
        imageRepository.save(CreatorSpaceProfileImage.uploadingBanner(key, creator.getCreatorId(), clock.instant()));
        return key;
    }

    public boolean markUploaded(String key) {
        return imageRepository.markUploaded(key, clock.instant()) == 1;
    }

    private Creator requireCreatorWithSpace(Long memberId) {
        Creator creator = creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        spaceRepository.findByCreatorId(creator.getCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return creator;
    }

    private NormalizedImage normalize(byte[] bytes, ImagePolicy policy, CreatorErrorCode invalidImageError) {
        try { return imageNormalizer.normalize(bytes, policy); }
        catch (InvalidImageException exception) {
            throw new BusinessException(invalidImageError);
        }
    }

    /** 업로드 기록을 남긴 뒤 저장소에 올리고 완료로 표시한다. 연결되지 않으면 정리 작업이 회수한다. */
    private String store(String key, NormalizedImage image) {
        objectStorage.put(key, image.normalizedImageBytes(), "image/jpeg");
        imageRepository.markUploaded(key, clock.instant());
        return key;
    }
}

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

@Service @RequiredArgsConstructor
public class CreatorSpaceProfileImageUploadService {
    private static final ImagePolicy POLICY = new ImagePolicy(200, 200, 2048);
    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceProfileImageRepository imageRepository;
    private final ImageNormalizer imageNormalizer;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    public String upload(Long memberId, byte[] bytes) {
        Creator creator = creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        spaceRepository.findByCreatorId(creator.getCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        NormalizedImage image;
        try { image = imageNormalizer.normalize(bytes, POLICY); }
        catch (InvalidImageException exception) {
            throw new BusinessException(CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);
        }
        String key = "creator-space-profile-images/%d/%s.jpg".formatted(creator.getCreatorId(), UUID.randomUUID());
        imageRepository.save(CreatorSpaceProfileImage.uploading(key, creator.getCreatorId(), clock.instant()));
        objectStorage.put(key, image.normalizedImageBytes(), "image/jpeg");
        imageRepository.markUploaded(key, clock.instant());
        return key;
    }
}

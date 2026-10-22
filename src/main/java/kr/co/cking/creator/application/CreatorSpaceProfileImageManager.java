package kr.co.cking.creator.application;

import java.time.Clock;
import java.util.Objects;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creator Space 잠금을 보유한 호출자가 업로드 이미지(프로필 사진·배너) 연결·기본 이미지 복귀를 원자적으로 적용한다.
 * 연결은 올린 종류와 같은 이미지만 받고, 교체·복귀로 연결이 풀린 이전 이미지는 삭제 대기로 돌린다.
 */
@Component
@RequiredArgsConstructor
public class CreatorSpaceProfileImageManager {

    private final CreatorSpaceProfileImageRepository imageRepository;
    private final CreatorSpaceProfileImageUrlResolver imageUrlResolver;
    private final Clock clock;

    public void apply(Creator creator, CreatorSpace space, String imageKey, boolean imageKeyProvided) {
        if (!imageKeyProvided || Objects.equals(imageKey, space.getProfileImageKey())) {
            return;
        }
        String oldKey = space.getProfileImageKey();
        var now = clock.instant();
        if (imageKey == null) {
            space.restoreDefaultProfileImage();
        } else {
            if (imageKey.isBlank() || imageRepository.link(imageKey, creator.getCreatorId(), now) != 1) {
                throw new BusinessException(CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);
            }
            space.updateProfileImage(imageKey, imageUrlResolver.resolve(imageKey));
        }
        if (oldKey != null) {
            imageRepository.markDeletePending(oldKey, creator.getCreatorId(), now);
        }
    }

    /**
     * 배너 이미지를 연결하거나 기본 배너로 되돌린다(이슈 #590). {@code imageKeyProvided}가 false면 현재 연결을 유지한다.
     * 키가 null이면 기본 배너로 복귀하고, 본인이 배너로 올린 업로드 완료 키면 교체한다. 그 밖의 키는
     * {@code INVALID_SPACE_BANNER_IMAGE}다.
     */
    public void applyBanner(Creator creator, CreatorSpace space, String imageKey, boolean imageKeyProvided) {
        if (!imageKeyProvided || Objects.equals(imageKey, space.getBannerImageKey())) {
            return;
        }
        String oldKey = space.getBannerImageKey();
        var now = clock.instant();
        if (imageKey == null) {
            space.restoreDefaultBannerImage();
        } else {
            if (imageKey.isBlank() || imageRepository.linkBanner(imageKey, creator.getCreatorId(), now) != 1) {
                throw new BusinessException(CreatorErrorCode.INVALID_SPACE_BANNER_IMAGE);
            }
            space.updateBannerImage(imageKey, imageUrlResolver.resolve(imageKey));
        }
        if (oldKey != null) {
            imageRepository.markDeletePending(oldKey, creator.getCreatorId(), now);
        }
    }
}

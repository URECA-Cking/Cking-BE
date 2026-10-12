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

/** Creator Space 잠금을 보유한 호출자가 업로드 이미지 연결·기본 이미지 복귀를 원자적으로 적용한다. */
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
}

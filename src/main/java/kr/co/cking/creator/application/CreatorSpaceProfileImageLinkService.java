package kr.co.cking.creator.application;

import java.time.Clock;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 업로드 완료된 Creator Space 이미지를 본인 Space에 연결한다. */
@Service @RequiredArgsConstructor
public class CreatorSpaceProfileImageLinkService {
    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceProfileImageRepository imageRepository;
    private final CreatorSpaceProfileImageUrlResolver imageUrlResolver;
    private final Clock clock;

    @Transactional
    public void link(Long memberId, String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            throw new BusinessException(CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);
        }
        Creator creator = creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        CreatorSpace space = spaceRepository.findByCreatorIdForUpdate(creator.getCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        var now = clock.instant();
        if (imageKey.equals(space.getProfileImageKey())) return;
        if (imageRepository.link(imageKey, creator.getCreatorId(), now) != 1) {
            throw new BusinessException(CreatorErrorCode.INVALID_SPACE_PROFILE_IMAGE);
        }
        String oldKey = space.getProfileImageKey();
        space.updateProfileImage(imageKey, imageUrlResolver.resolve(imageKey));
        if (oldKey != null) imageRepository.markDeletePending(oldKey, creator.getCreatorId(), now);
    }
}

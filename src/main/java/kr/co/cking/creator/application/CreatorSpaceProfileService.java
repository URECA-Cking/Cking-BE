package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceCustomSlug;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Creator Space 홈·프로필 조회와 Creator 본인의 수정·커스텀 slug 변경을 처리한다(이슈 #286, #290).
 * 본인 API는 호출자 memberId로 Creator를 찾으므로 다른 Creator의 Space에는 접근할 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorSpaceProfileService {

    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository spaceRepository;
    private final Clock clock;

    public CreatorSpaceView findByCreatorId(Long creatorId) {
        Creator creator = creatorRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new CreatorSpaceView(requireSpace(creatorId), creator.getName());
    }

    public CreatorSpaceView findMine(Long memberId) {
        Creator creator = requireCreator(memberId);
        return new CreatorSpaceView(requireSpace(creator.getCreatorId()), creator.getName());
    }

    @Transactional
    public CreatorSpaceView updateMine(Long memberId, CreatorSpaceProfileFields fields) {
        Creator creator = requireCreator(memberId);
        CreatorSpace space = requireSpaceForUpdate(creator.getCreatorId());
        space.updateProfile(fields.introText(), fields.profileImageUrl(), fields.bannerImageUrl());
        return new CreatorSpaceView(space, creator.getName());
    }

    public CreatorSpaceView findBySlug(String slug) {
        CreatorSpace space = spaceRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Creator creator = creatorRepository.findById(space.getCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new CreatorSpaceView(space, creator.getName());
    }

    /**
     * 커스텀 slug로 바꾼다. 규칙은 docs/domains/creator/space-slug-policy.md를 따른다.
     * 먼저 조회로 중복을 걸러내고, 동시에 같은 slug를 요청한 경우는 DB UNIQUE 제약으로 막는다.
     */
    @Transactional
    public CreatorSpaceView changeSlug(Long memberId, String slug) {
        Creator creator = requireCreator(memberId);
        CreatorSpace space = requireSpaceForUpdate(creator.getCreatorId());
        if (space.getSlug().equals(slug)) {
            return new CreatorSpaceView(space, creator.getName());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!space.canChangeSlugAt(now)) {
            throw new BusinessException(CreatorErrorCode.SLUG_CHANGE_TOO_SOON);
        }
        if (!CreatorSpaceCustomSlug.isValidFormat(slug)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (CreatorSpaceCustomSlug.isReserved(slug)) {
            throw new BusinessException(CreatorErrorCode.RESERVED_SLUG);
        }
        if (spaceRepository.existsBySlug(slug)) {
            throw new BusinessException(CreatorErrorCode.SLUG_ALREADY_TAKEN);
        }
        space.changeSlug(slug, now);
        try {
            spaceRepository.saveAndFlush(space);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(CreatorErrorCode.SLUG_ALREADY_TAKEN);
        }
        return new CreatorSpaceView(space, creator.getName());
    }

    private Creator requireCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    /** V19 백필이 건너뛰어진 기존 Creator처럼 Space가 없을 수 있다. */
    private CreatorSpace requireSpace(Long creatorId) {
        return spaceRepository.findByCreatorId(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private CreatorSpace requireSpaceForUpdate(Long creatorId) {
        return spaceRepository.findByCreatorIdForUpdate(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

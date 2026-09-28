package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceSlugRule;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 트랜잭션에서 활성 템플릿을 복사해 Space를 만든다. 템플릿이 없거나 slug 규칙이
 * 유효하지 않으면 예외를 던져 승인 전체를 롤백한다. 기존 템플릿 데이터도 생성 직전에 검증한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorSpaceService {

    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceTemplateService templateService;

    /** 이미 Space가 있으면 그대로 반환한다. */
    public CreatorSpace createFromActiveTemplateIfAbsent(Long creatorId) {
        return spaceRepository.findByCreatorId(creatorId)
                .orElseGet(() -> createNew(creatorId));
    }

    private CreatorSpace createNew(Long creatorId) {
        CreatorSpaceTemplate template = templateService.findActive()
                .orElseThrow(() -> new BusinessException(CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE));
        String slug = buildSlug(template.getSlugRule(), creatorId);
        return spaceRepository.save(CreatorSpace.fromTemplate(creatorId, template, slug));
    }

    private String buildSlug(String slugRule, Long creatorId) {
        if (!CreatorSpaceSlugRule.isValid(slugRule)) {
            throw new BusinessException(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        }
        String slug = CreatorSpaceSlugRule.apply(slugRule, creatorId);
        if (slug.length() > CreatorSpaceSlugRule.MAX_SLUG_LENGTH) {
            throw new BusinessException(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        }
        return slug;
    }
}

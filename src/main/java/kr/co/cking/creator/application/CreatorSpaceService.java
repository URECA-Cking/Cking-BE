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

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 승인 트랜잭션에서 활성 템플릿을 복사해 Space를 만든다. 템플릿이 없거나 slug 규칙이
 * 유효하지 않으면 예외를 던져 승인 전체를 롤백한다. 기존 템플릿 데이터도 생성 직전에 검증한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorSpaceService {

    private static final int MAX_FALLBACK_SUFFIX = 100;

    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceTemplateService templateService;
    private final CreatorSpaceSlugRegistry slugRegistry;
    private final Clock clock;

    /** 이미 Space가 있으면 그대로 반환한다. */
    public CreatorSpace createFromActiveTemplateIfAbsent(Long creatorId) {
        return spaceRepository.findByCreatorId(creatorId)
                .orElseGet(() -> createNew(creatorId));
    }

    private CreatorSpace createNew(Long creatorId) {
        CreatorSpaceTemplate template = templateService.findActive()
                .orElseThrow(() -> new BusinessException(CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE));
        LocalDateTime now = LocalDateTime.now(clock);
        String slug = availableSlug(buildSlug(template.getSlugRule(), creatorId), creatorId, now);
        slugRegistry.clearReservation(slug);
        return spaceRepository.save(CreatorSpace.fromTemplate(creatorId, template, slug));
    }

    /**
     * 다른 Creator가 커스텀 slug로 이 자동 slug를 먼저 가져갔거나, 버린 slug로 예약해 두었을 수 있다.
     * 그때는 `-2`, `-3`처럼 번호를 붙여 비어 있는 값을 써서 승인이 slug 충돌로 실패하지 않게 한다.
     */
    private String availableSlug(String base, Long creatorId, LocalDateTime now) {
        if (!slugRegistry.isTaken(base, creatorId, now)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_FALLBACK_SUFFIX; suffix++) {
            String candidate = base + "-" + suffix;
            if (candidate.length() > CreatorSpaceSlugRule.MAX_SLUG_LENGTH) {
                break;
            }
            if (!slugRegistry.isTaken(candidate, creatorId, now)) {
                return candidate;
            }
        }
        throw new BusinessException(CreatorErrorCode.SLUG_ALREADY_TAKEN);
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

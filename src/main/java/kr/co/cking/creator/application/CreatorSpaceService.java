package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator 승인 시점에 활성 기본 템플릿을 복사해 Creator Space를 만든다(이슈 #270).
 * {@link #createFromActiveTemplateIfAbsent}는 {@link CreatorApplicationService}의 승인
 * 트랜잭션(기본값 REQUIRED)에 참여한다 — Space 생성이 실패하면(활성 템플릿 없음·slugRule
 * 무효 포함) Creator 생성·기본 Mission 초기화·신청 승인 상태 전이도 함께 롤백돼, "Creator는
 * 승인됐는데 Space가 없는" 상태가 생기지 않는다.
 *
 * <p>활성 템플릿이 없으면 별도 상태를 만들지 않고 승인 자체를 {@link CreatorErrorCode#NO_ACTIVE_SPACE_TEMPLATE}로
 * 실패시킨다 — 관리자가 기본 템플릿을 먼저 활성화해야 크리에이터 승인을 진행할 수 있다.
 *
 * <p>{@code CreatorSpaceTemplateRequest}의 Bean Validation은 새로 생성·수정하는 템플릿의
 * {@code slugRule}만 검증한다. 그 검증이 생기기 전에 이미 저장돼 활성 상태로 남아 있는
 * 템플릿은 이 검증을 거치지 않았을 수 있으므로, 여기서 활성 템플릿을 읽은 뒤 slug를 만들기
 * 직전에 같은 규칙(자리표시자 정확히 1회, 치환 결과가 {@code creator_space.slug} 컬럼
 * 길이 이내)을 다시 검증한다. 그렇지 않으면 DB UNIQUE 제약·컬럼 길이 초과가 예측 불가능한
 * 원인 불명의 예외로 승인 트랜잭션을 실패시킨다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorSpaceService {

    private static final String SLUG_CREATOR_ID_PLACEHOLDER = "{creatorId}";
    private static final int MAX_SLUG_LENGTH = 100;

    private final CreatorSpaceRepository spaceRepository;
    private final CreatorSpaceTemplateService templateService;

    /**
     * 이미 Space가 있으면 새로 만들지 않고 그대로 반환한다 — 승인 재시도·중복 호출에도
     * Space가 두 개 생기지 않도록 하기 위해서다. {@code creator.member_id`의 UNIQUE
     * 제약상 승인은 크리에이터당 한 번만 성공하므로 현재 호출 경로에서 동시 호출은
     * 없지만, 이 메서드 자체도 재호출에 안전하도록 방어적으로 조회부터 한다.
     */
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
        if (countOccurrences(slugRule, SLUG_CREATOR_ID_PLACEHOLDER) != 1) {
            throw new BusinessException(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        }
        String slug = slugRule.replace(SLUG_CREATOR_ID_PLACEHOLDER, String.valueOf(creatorId));
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new BusinessException(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        }
        return slug;
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = value.indexOf(token);
        while (index != -1) {
            count++;
            index = value.indexOf(token, index + token.length());
        }
        return count;
    }
}

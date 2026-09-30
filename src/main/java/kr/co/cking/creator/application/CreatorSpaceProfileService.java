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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final CreatorSpaceSlugRegistry slugRegistry;
    private final Clock clock;

    /** Creator ID로 공개 Creator Space와 Creator 이름을 조회한다. */
    public CreatorSpaceView findByCreatorId(Long creatorId) {
        Creator creator = requireCreatorById(creatorId);
        return new CreatorSpaceView(requireSpace(creatorId), creator.getName());
    }

    /** 공유 URL slug를 Creator ID로 해석해 기존 공개 Creator Space 응답을 만든다. */
    public CreatorSpaceView findBySlug(String slug) {
        CreatorSpace space = spaceRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Creator creator = requireCreatorById(space.getCreatorId());
        return new CreatorSpaceView(space, creator.getName());
    }

    /**
     * 공개 Creator 목록을 이름 오름차순(같으면 creatorId 오름차순)으로 조회한다(이슈 #352).
     * keyword가 없거나 공백이면 전체를, 있으면 Creator 이름에 포함된(대소문자 무시) 항목만 반환한다.
     * Space가 없는 Creator는 공개 대상이 아니므로 제외된다.
     */
    public Page<CreatorSpaceView> findPublicSpaces(String keyword, Pageable pageable) {
        Page<CreatorSpace> spaces = spaceRepository.findAllByCreatorNamePattern(toNamePattern(keyword), pageable);
        Map<Long, String> creatorNames = creatorRepository
                .findByCreatorIdIn(spaces.map(CreatorSpace::getCreatorId).toList())
                .stream()
                .collect(Collectors.toMap(Creator::getCreatorId, Creator::getName));
        return spaces.map(space -> new CreatorSpaceView(space, creatorNames.get(space.getCreatorId())));
    }

    /** LIKE 특수문자({@code %}, {@code _})와 이스케이프 문자({@code !})를 이스케이프해 포함 검색 패턴을 만든다. */
    private String toNamePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "%";
        }
        String escaped = keyword.trim()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    /** 인증된 Creator 본인의 Creator Space를 조회한다. */
    public CreatorSpaceView findMine(Long memberId) {
        Creator creator = requireCreator(memberId);
        return new CreatorSpaceView(requireSpace(creator.getCreatorId()), creator.getName());
    }

    /** 인증된 Creator 본인의 Creator Space 프로필 값을 모두 갱신한다. */
    @Transactional
    public CreatorSpaceView updateMine(Long memberId, CreatorSpaceProfileFields fields) {
        Creator creator = requireCreator(memberId);
        CreatorSpace space = requireSpaceForUpdate(creator.getCreatorId());
        space.updateProfile(fields.introText(), fields.profileImageUrl(), fields.bannerImageUrl());
        return new CreatorSpaceView(space, creator.getName());
    }

    /**
     * 커스텀 slug로 바꾼다. 규칙은 docs/domains/creator/space-slug-policy.md를 따른다.
     * 먼저 조회로 중복·예약을 걸러내고, 동시에 같은 slug를 요청한 경우는 DB UNIQUE 제약으로 막는다.
     * 버린 이전 slug는 같은 트랜잭션에서 예약해 다른 Creator가 곧바로 가져가지 못하게 한다(이슈 #301).
     *
     * <p>본인이 예약해 둔 이전 slug로 돌아가는 되돌리기는 14일 변경 제한을 받지 않는다. 이미 쓰던 값이므로
     * 형식·예약어 검사도 다시 하지 않는다. 대신 변경 시각을 갱신하지 않아 새 slug로의 변경 제한은 그대로다.
     */
    @Transactional
    public CreatorSpaceView changeSlug(Long memberId, String slug) {
        Creator creator = requireCreator(memberId);
        Long creatorId = creator.getCreatorId();
        CreatorSpace space = requireSpaceForUpdate(creatorId);
        if (space.getSlug().equals(slug)) {
            return new CreatorSpaceView(space, creator.getName());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean revert = slugRegistry.isReservedBy(slug, creatorId, now);
        if (!revert) {
            validateNewSlug(space, slug, creatorId, now);
        }
        String releasedSlug = space.getSlug();
        slugRegistry.clearReservation(slug);
        if (revert) {
            space.revertSlug(slug);
        } else {
            space.changeSlug(slug, now);
        }
        slugRegistry.reserveReleased(releasedSlug, creatorId, now);
        try {
            spaceRepository.saveAndFlush(space);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(CreatorErrorCode.SLUG_ALREADY_TAKEN);
        }
        return new CreatorSpaceView(space, creator.getName());
    }

    private void validateNewSlug(CreatorSpace space, String slug, Long creatorId, LocalDateTime now) {
        if (!space.canChangeSlugAt(now)) {
            throw new BusinessException(CreatorErrorCode.SLUG_CHANGE_TOO_SOON);
        }
        if (!CreatorSpaceCustomSlug.isValidFormat(slug)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (CreatorSpaceCustomSlug.isReserved(slug)) {
            throw new BusinessException(CreatorErrorCode.RESERVED_SLUG);
        }
        if (slugRegistry.isTaken(slug, creatorId, now)) {
            throw new BusinessException(CreatorErrorCode.SLUG_ALREADY_TAKEN);
        }
    }

    /** 회원 ID에 연결된 Creator를 조회하고 없으면 업무 권한 오류를 반환한다. */
    private Creator requireCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    /** Creator ID에 해당하는 Creator를 조회하고 없으면 리소스 없음 오류를 반환한다. */
    private Creator requireCreatorById(Long creatorId) {
        return creatorRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** V19 백필이 건너뛰어진 기존 Creator처럼 Space가 없을 수 있다. */
    private CreatorSpace requireSpace(Long creatorId) {
        return spaceRepository.findByCreatorId(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 수정·slug 변경에 사용할 Creator Space를 비관적 잠금으로 조회한다. */
    private CreatorSpace requireSpaceForUpdate(Long creatorId) {
        return spaceRepository.findByCreatorIdForUpdate(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}

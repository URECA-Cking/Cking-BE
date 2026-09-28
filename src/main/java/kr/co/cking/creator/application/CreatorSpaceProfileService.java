package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator Space 홈·프로필 조회와 Creator 본인의 수정을 처리한다(이슈 #286).
 * 본인 API는 호출자 memberId로 Creator를 찾으므로 다른 Creator의 Space에는 접근할 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorSpaceProfileService {

    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository spaceRepository;

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

    /** 인증된 Creator 본인의 Creator Space를 조회한다. */
    public CreatorSpaceView findMine(Long memberId) {
        Creator creator = requireCreator(memberId);
        return new CreatorSpaceView(requireSpace(creator.getCreatorId()), creator.getName());
    }

    /** 인증된 Creator 본인의 Creator Space 프로필 값을 모두 갱신한다. */
    @Transactional
    public CreatorSpaceView updateMine(Long memberId, CreatorSpaceProfileFields fields) {
        Creator creator = requireCreator(memberId);
        CreatorSpace space = requireSpace(creator.getCreatorId());
        space.updateProfile(
                fields.introText(), fields.profileImageUrl(), fields.bannerImageUrl(),
                fields.homeTabEnabled(), fields.missionsTabEnabled(), fields.postsTabEnabled(), fields.eventsTabEnabled()
        );
        return new CreatorSpaceView(space, creator.getName());
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
}

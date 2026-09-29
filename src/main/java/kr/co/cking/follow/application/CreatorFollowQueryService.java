package kr.co.cking.follow.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.dto.FollowedCreatorView;
import kr.co.cking.follow.domain.CreatorFollow;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

/** 팔로우 관계를 읽기 전용으로 조회한다. 다른 도메인은 {@link #isFollowing}으로 팔로우 여부를 확인한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorFollowQueryService {

    private final CreatorRepository creatorRepository;
    private final CreatorFollowRepository followRepository;

    /**
     * 다른 도메인용 팔로우 여부 조회. 비로그인 사용자({@code memberId == null})는 팔로우하지 않은 것으로 본다.
     * 크리에이터 존재 여부는 검증하지 않는다.
     */
    public boolean isFollowing(Long memberId, Long creatorId) {
        if (memberId == null || creatorId == null) {
            return false;
        }
        return followRepository.existsByMemberIdAndCreatorId(memberId, creatorId);
    }

    /** API용 팔로우 여부 조회. 존재하지 않는 크리에이터는 404다. */
    public boolean findFollowStatus(Long memberId, Long creatorId) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return followRepository.existsByMemberIdAndCreatorId(memberId, creatorId);
    }

    /** 내가 팔로우한 크리에이터를 최근 팔로우 순으로 조회한다. */
    public Page<FollowedCreatorView> findMine(Long memberId, Pageable pageable) {
        Page<CreatorFollow> follows = followRepository.findByMemberIdLatestFirst(memberId, pageable);
        Map<Long, String> creatorNames = creatorRepository
                .findByCreatorIdIn(follows.map(CreatorFollow::getCreatorId).toList())
                .stream()
                .collect(Collectors.toMap(Creator::getCreatorId, Creator::getName, (first, second) -> first));
        return follows.map(follow -> new FollowedCreatorView(
                follow.getCreatorId(), creatorNames.get(follow.getCreatorId()), follow.getCreatedAt()));
    }
}

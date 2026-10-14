package kr.co.cking.creator.application;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;

/**
 * 여러 Creator의 공개 이름·프로필 이미지를 한 번에 읽는 읽기 전용 조회.
 * 다른 도메인이 Creator·Space Repository를 직접 들고 같은 Map 조립을 반복하지 않도록 노출 규칙을 이곳에 모은다.
 * 공개 이름은 활동명({@link Creator#publicDisplayName()}), 이미지는 Space의 프로필 이미지 URL이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorPublicProfileQueryService {

    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository creatorSpaceRepository;

    /**
     * 조회한 Creator ID별 공개 프로필을 돌려준다. 존재하지 않는 Creator는 Map에 없고,
     * Space가 없는 Creator의 {@code profileImageUrl}은 null이다. ID마다 한 번씩만 읽는다(반복 조회 없음).
     * Creator ID와 Space의 creator_id는 각각 유일하므로 키 충돌은 없다.
     */
    public Map<Long, CreatorPublicProfile> findByCreatorIds(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CreatorSpace> spaces = creatorSpaceRepository.findByCreatorIdIn(creatorIds).stream()
                .collect(Collectors.toMap(CreatorSpace::getCreatorId, Function.identity()));
        return creatorRepository.findByCreatorIdIn(creatorIds).stream()
                .collect(Collectors.toMap(Creator::getCreatorId, creator -> {
                    CreatorSpace space = spaces.get(creator.getCreatorId());
                    return new CreatorPublicProfile(
                            creator.getCreatorId(),
                            creator.publicDisplayName(),
                            space == null ? null : space.getProfileImageUrl());
                }));
    }
}

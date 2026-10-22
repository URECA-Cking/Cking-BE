package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMission;
import kr.co.cking.mission.CommonMissionCompletionRepository;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.application.dto.CommonMissionQueryItem;
import kr.co.cking.mission.domain.CommonMissionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link MissionQueryService}와 동일 계약(활성 미션 조회 + userId 기준 오늘
 * completedToday 일괄 조회)을 크리에이터 축 없이 제공한다(이슈 #219).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonMissionQueryService {

    private static final List<CommonMissionType> SUPPORTED_TYPES = List.of(CommonMissionType.ATTENDANCE);

    private final MemberRepository memberRepository;
    private final CommonMissionRepository missionRepository;
    private final CommonMissionCompletionRepository completionRepository;
    private final Clock clock;

    public List<CommonMissionQueryItem> findMissions(Long userId) {
        if (!memberRepository.existsById(userId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }

        var now = clock.instant();
        List<CommonMission> activeMissions = missionRepository.findByTypeIn(SUPPORTED_TYPES)
                .stream()
                .filter(mission -> mission.isActiveAt(now))
                .toList();

        if (activeMissions.isEmpty()) {
            return List.of();
        }

        Set<Long> missionIds = activeMissions.stream()
                .map(CommonMission::getMissionId)
                .collect(Collectors.toSet());
        String utcPeriodKey = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        Set<Long> completedMissionIds = completionRepository
                .findAllByMemberIdAndMissionIdInAndPeriodKey(userId, missionIds, utcPeriodKey)
                .stream()
                .map(completion -> completion.getMissionId())
                .collect(Collectors.toSet());

        return activeMissions.stream()
                .map(mission -> new CommonMissionQueryItem(
                        mission.getMissionId(),
                        mission.getType(),
                        mission.getRewardAmount(),
                        mission.getActiveFrom(),
                        mission.getActiveTo(),
                        completedMissionIds.contains(mission.getMissionId())))
                .toList();
    }
}

package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.dto.MissionQueryItem;
import kr.co.cking.mission.domain.MissionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionQueryService {

    private static final List<MissionType> SUPPORTED_TYPES = List.of(MissionType.ATTENDANCE, MissionType.LIKE);

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final MissionRepository missionRepository;
    private final MissionCompletionRepository completionRepository;
    private final Clock clock;

    public List<MissionQueryItem> findMissions(Long creatorId, Long userId) {
        if (!memberRepository.existsById(userId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }

        var now = clock.instant();
        List<Mission> activeMissions = missionRepository.findByCreatorIdAndTypeIn(creatorId, SUPPORTED_TYPES)
                .stream()
                .filter(mission -> mission.isActiveAt(now))
                .toList();

        if (activeMissions.isEmpty()) {
            return List.of();
        }

        Set<Long> missionIds = activeMissions.stream()
                .map(Mission::getMissionId)
                .collect(Collectors.toSet());
        String utcPeriodKey = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        Set<Long> completedMissionIds = completionRepository
                .findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(userId, creatorId, missionIds, utcPeriodKey)
                .stream()
                .map(completion -> completion.getMissionId())
                .collect(Collectors.toSet());

        return activeMissions.stream()
                .map(mission -> new MissionQueryItem(
                        mission.getMissionId(),
                        mission.getType(),
                        mission.getRewardAmount(),
                        mission.getActiveFrom(),
                        mission.getActiveTo(),
                        completedMissionIds.contains(mission.getMissionId())))
                .toList();
    }
}

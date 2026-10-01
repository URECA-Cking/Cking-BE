package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionCompletion;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.dto.MissionQueryItem;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionQueryService {

    private static final List<MissionType> SUPPORTED_TYPES = List.of(
            MissionType.LIKE,
            MissionType.SHARE,
            MissionType.YOUTUBE_SUBSCRIPTION
    );

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final MissionRepository missionRepository;
    private final MissionCompletionRepository completionRepository;
    private final Clock clock;

    /** 인증된 사용자를 기준으로 Creator의 활성 미션과 DAILY·ONCE 정책별 완료 여부를 조회한다. */
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

        Set<Long> dailyMissionIds = activeMissions.stream()
                .filter(mission -> mission.getType() == MissionType.LIKE)
                .map(Mission::getMissionId)
                .collect(Collectors.toSet());
        Set<Long> onceMissionIds = activeMissions.stream()
                .filter(mission -> mission.getType() == MissionType.SHARE
                        || mission.getType() == MissionType.YOUTUBE_SUBSCRIPTION)
                .map(Mission::getMissionId)
                .collect(Collectors.toSet());
        String utcPeriodKey = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        Set<Long> completedMissionIds = new HashSet<>();
        if (!dailyMissionIds.isEmpty()) {
            completedMissionIds.addAll(completionRepository
                    .findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
                            userId, creatorId, dailyMissionIds, utcPeriodKey)
                    .stream()
                    .map(MissionCompletion::getMissionId)
                    .collect(Collectors.toSet()));
        }
        if (!onceMissionIds.isEmpty()) {
            completedMissionIds.addAll(completionRepository
                    .findAllByMemberIdAndCreatorIdAndMissionIdInAndCompletionKey(
                            userId, creatorId, onceMissionIds, EarnRewardPolicy.ONCE.completionKey(null))
                    .stream()
                    .map(MissionCompletion::getMissionId)
                    .collect(Collectors.toSet()));
        }

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

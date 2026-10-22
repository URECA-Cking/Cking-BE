package kr.co.cking.mission.application;

import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creator의 YouTube 채널 최초 설정 Transaction 안에서 구독 인증 미션을 멱등 준비한다. */
@Service
@RequiredArgsConstructor
public class YoutubeSubscriptionMissionProvisioningService {

    private static final int REWARD_AMOUNT = 1;

    private final MissionRepository missionRepository;

    /** 호출자의 Transaction에 반드시 참여해 채널과 미션이 함께 commit 또는 rollback되게 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Mission provision(Long creatorId) {
        return missionRepository.findByCreatorIdAndType(creatorId, MissionType.YOUTUBE_SUBSCRIPTION)
                .orElseGet(() -> missionRepository.save(
                        new Mission(creatorId, MissionType.YOUTUBE_SUBSCRIPTION, REWARD_AMOUNT, null, null)));
    }
}

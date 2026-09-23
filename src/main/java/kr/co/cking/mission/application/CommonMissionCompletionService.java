package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMission;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.ticket.application.CommonTicketEarnService;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 공용 미션(크리에이터 무관) 완료 처리 + 공용 EARN 연동(이슈 #219).
 * {@link MissionCompletionService}와 완전히 동일한 판정 순서(기존 requestId 조회
 * → 활성 검증 → EARN 호출)를 크리에이터 축 없이 수행한다. {@code MissionCompleteCommand}/
 * {@code MissionCompleteOutcome}은 원래도 creatorId를 담지 않는 범용 DTO라 그대로 재사용한다.
 */
@Service
@RequiredArgsConstructor
public class CommonMissionCompletionService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final MemberRepository memberRepository;
    private final CommonMissionRepository commonMissionRepository;
    private final CommonTicketEarnService commonTicketEarnService;
    private final Clock clock;

    public MissionCompleteOutcome complete(Long missionId, MissionCompleteCommand command) {
        memberRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        CommonMission mission = commonMissionRepository.findByMissionId(missionId)
                .orElseThrow(() -> new BusinessException(MissionErrorCode.MISSION_NOT_FOUND));

        Instant now = clock.instant();
        String periodKey = periodKeyOf(now);
        CommonEarnCommand earnCommand = new CommonEarnCommand(
                command.requestId(),
                command.userId(),
                mission.getType().name(),
                missionId,
                periodKey,
                mission.getRewardAmount().longValue()
        );

        EarnLookupStatus lookupStatus = commonTicketEarnService.findExisting(earnCommand).status();
        if (lookupStatus == EarnLookupStatus.ALREADY_PROCESSED) {
            return outcomeOf(EarnResultCode.ALREADY_PROCESSED, missionId, mission, now);
        }
        if (lookupStatus != EarnLookupStatus.NOT_FOUND) {
            throw new BusinessException(MissionErrorCode.from(lookupStatus));
        }

        if (!mission.isActiveAt(now)) {
            throw new BusinessException(MissionErrorCode.MISSION_INACTIVE);
        }

        EarnResult result = commonTicketEarnService.earn(earnCommand);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            return outcomeOf(result.code(), missionId, mission, now);
        }
        throw new BusinessException(MissionErrorCode.from(result.code()));
    }

    private MissionCompleteOutcome outcomeOf(EarnResultCode code, Long missionId, CommonMission mission, Instant now) {
        return new MissionCompleteOutcome(code, missionId, mission.getRewardAmount(), now);
    }

    private String periodKeyOf(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().format(PERIOD_KEY_FORMAT);
    }
}

package kr.co.cking.mission.application;

import kr.co.cking.abuse.application.MissionAbuseObserver;
import kr.co.cking.abuse.application.MissionObservationContext;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMission;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.MissionCompletionRepository;
import kr.co.cking.mission.domain.MissionType;
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
 * {@link MissionCompletionService}와 동일하게 기존 requestId replay를 먼저 처리한 뒤
 * 활성 검증과 EARN을 수행한다. 공용 ATTENDANCE 신규 EARN 전에는 같은 UTC 날짜의
 * 기존 Creator ATTENDANCE 완료 기록도 확인해 서비스 전체 하루 1회 정책을 적용한다.
 * {@code MissionCompleteCommand}/{@code MissionCompleteOutcome}은 creatorId가 없는 공용
 * 계약이라 그대로 재사용한다.
 */
@Service
@RequiredArgsConstructor
public class CommonMissionCompletionService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final MemberRepository memberRepository;
    private final CommonMissionRepository commonMissionRepository;
    private final MissionCompletionRepository creatorCompletionRepository;
    private final CommonTicketEarnService commonTicketEarnService;
    private final Clock clock;
    private final MissionAbuseObserver abuseObserver;

    public MissionCompleteOutcome complete(Long missionId, MissionCompleteCommand command) {
        memberRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        CommonMission mission = commonMissionRepository.findByMissionId(missionId)
                .orElseThrow(() -> new BusinessException(MissionErrorCode.MISSION_NOT_FOUND));

        Instant now = clock.instant();
        MissionObservationContext observation = MissionObservationContext.common(
                command.userId(), missionId, command.requestId(), now, mission.getType());
        MissionCompleteOutcome outcome;
        try {
            outcome = completeValidated(missionId, command, mission, now);
        } catch (BusinessException exception) {
            abuseObserver.observeFailure(observation, exception.getErrorCode());
            throw exception;
        } catch (RuntimeException exception) {
            abuseObserver.observeUnexpectedFailure(observation);
            throw exception;
        }
        abuseObserver.observeSuccess(observation, outcome.code());
        return outcome;
    }

    /** 검증된 공용 미션의 기존 EARN·replay 계약을 수행한다. */
    private MissionCompleteOutcome completeValidated(
            Long missionId, MissionCompleteCommand command, CommonMission mission, Instant now
    ) {
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

        if (creatorCompletionRepository.existsByMemberIdAndPeriodKeyAndMissionType(
                command.userId(), periodKey, MissionType.ATTENDANCE.name()) == 1L) {
            throw new BusinessException(MissionErrorCode.DUPLICATE_MISSION);
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

package kr.co.cking.mission.application;

import kr.co.cking.abuse.application.MissionAbuseObserver;
import kr.co.cking.abuse.application.MissionObservationContext;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Creator Space 공유 완료를 SHARE 미션의 Creator 전용 응모권 적립으로 연결한다. */
@Service
@RequiredArgsConstructor
public class CreatorSpaceShareMissionCompletionService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final MissionRepository missionRepository;
    private final TicketOnceEarnService ticketOnceEarnService;
    private final Clock clock;
    private final MissionAbuseObserver abuseObserver;

    /** Creator Space 공유를 검증하고 SHARE 미션 보상을 멱등하게 요청한다. */
    public MissionCompleteOutcome complete(Long creatorId, MissionCompleteCommand command) {
        validateMember(command.userId());
        validateCreator(creatorId);

        Mission mission = findShareMission(creatorId);
        validateMissionOwner(mission, creatorId);

        Instant now = clock.instant();
        MissionObservationContext observation = MissionObservationContext.creator(
                command.userId(), creatorId, mission.getMissionId(), command.requestId(), now, MissionType.SHARE);
        MissionCompleteOutcome outcome;
        try {
            outcome = completeValidated(creatorId, command, mission, now);
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

    /** 검증된 SHARE 미션의 기존 ONCE EARN·replay 계약을 수행한다. */
    private MissionCompleteOutcome completeValidated(
            Long creatorId, MissionCompleteCommand command, Mission mission, Instant now
    ) {
        EarnCommand earnCommand = new EarnCommand(
                command.requestId(),
                command.userId(),
                creatorId,
                MissionType.SHARE.name(),
                mission.getMissionId(),
                periodKeyOf(now),
                missionKeyOf(creatorId),
                mission.getRewardAmount().longValue(),
                EarnRewardPolicy.ONCE
        );

        EarnLookupStatus lookupStatus = ticketOnceEarnService.findExisting(earnCommand).status();
        if (lookupStatus == EarnLookupStatus.ALREADY_PROCESSED) {
            return outcomeOf(EarnResultCode.ALREADY_PROCESSED, mission, now);
        }
        if (lookupStatus != EarnLookupStatus.NOT_FOUND) {
            throw new BusinessException(MissionErrorCode.from(lookupStatus));
        }
        if (!mission.isActiveAt(now)) {
            throw new BusinessException(MissionErrorCode.MISSION_INACTIVE);
        }

        EarnResult result = ticketOnceEarnService.earn(earnCommand);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            return outcomeOf(result.code(), mission, now);
        }
        throw new BusinessException(MissionErrorCode.from(result.code()));
    }

    /** 호출자 Member가 실제 존재하는지 확인한다. */
    private void validateMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /** 공유 대상 Creator가 실제 존재하는지 확인한다. */
    private void validateCreator(Long creatorId) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /** 대상 Creator에 연결된 SHARE 미션을 조회한다. */
    private Mission findShareMission(Long creatorId) {
        return missionRepository.findByCreatorIdAndType(creatorId, MissionType.SHARE)
                .orElseThrow(() -> new BusinessException(MissionErrorCode.MISSION_NOT_FOUND));
    }

    /** 조회된 SHARE 미션이 요청한 Creator의 미션인지 한 번 더 검증한다. */
    private void validateMissionOwner(Mission mission, Long creatorId) {
        if (!creatorId.equals(mission.getCreatorId())) {
            throw new BusinessException(MissionErrorCode.MISSION_NOT_FOUND);
        }
    }

    /** EARN 결과를 외부 완료 응답에 필요한 값으로 변환한다. */
    private MissionCompleteOutcome outcomeOf(EarnResultCode code, Mission mission, Instant now) {
        return new MissionCompleteOutcome(code, mission.getMissionId(), mission.getRewardAmount(), now);
    }

    /** 서버 UTC 날짜를 SHARE 미션의 일일 Business Key 기간 값으로 만든다. */
    private String periodKeyOf(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().format(PERIOD_KEY_FORMAT);
    }

    /** 날짜와 무관하게 같은 공유 미션을 식별하는 EARN fingerprint 구성 값을 만든다. */
    private String missionKeyOf(Long creatorId) {
        return "%s:%d".formatted(MissionType.SHARE.name().toLowerCase(Locale.ROOT), creatorId);
    }
}

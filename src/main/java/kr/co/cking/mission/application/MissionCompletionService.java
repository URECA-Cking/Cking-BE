package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
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
 * 미션 완료 처리 + EARN 연동(FR-P1-005~008, 014~016).
 *
 * <p>{@code mission_completion} 저장·EARN Ledger·DB Balance 반영은 이 계층의 책임이
 * 아니다 — {@link TicketEarnService#earn}이 Redis Lua로 멱등성·중복 적립 가드·Balance
 * 증가·Stream 발행을 원자 처리하고, EARN Stream Consumer가 그 이후 비동기로
 * {@code mission_completion}/{@code ticket_ledger}/{@code user_ticket_balance}를 한
 * 트랜잭션에 반영한다(취합v1.5.4 §4.5). 이 API가 {@code mission_completion}을 먼저
 * 써버리면 Consumer가 재전달로 오판해 Ledger·Balance 반영을 건너뛰므로, 이 클래스는
 * Redis/Stream 계층(EARN 결과코드) 밖의 어떤 영속 상태도 직접 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MissionCompletionService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final MemberRepository memberRepository;
    private final MissionRepository missionRepository;
    private final TicketEarnService ticketEarnService;
    private final Clock clock;

    public MissionCompleteOutcome complete(Long creatorId, Long missionId, MissionCompleteCommand command) {
        memberRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Mission mission = missionRepository.findByMissionIdAndCreatorId(missionId, creatorId)
                .orElseThrow(() -> new BusinessException(MissionErrorCode.MISSION_NOT_FOUND));

        Instant now = clock.instant();
        if (!mission.isActiveAt(now)) {
            throw new BusinessException(MissionErrorCode.MISSION_INACTIVE);
        }

        String periodKey = periodKeyOf(now);
        EarnCommand earnCommand = new EarnCommand(
                command.requestId(),
                command.userId(),
                creatorId,
                mission.getType().name(),
                missionId,
                periodKey,
                missionKeyOf(mission, creatorId, periodKey),
                mission.getRewardAmount().longValue()
        );

        EarnResult result = ticketEarnService.earn(earnCommand);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            return new MissionCompleteOutcome(result.code(), missionId, mission.getRewardAmount(), now);
        }
        throw new BusinessException(MissionErrorCode.from(result.code()));
    }

    /**
     * 업무일 경계는 서버 UTC 기준이다(RTM FR-P1-006/FR-P1-021/FR-P2-006, 취합v1.5.4
     * §4.2 — PR #63 리뷰에서 UTC로 최종 확정). {@link TicketEarnService}는 이 값을
     * {@code yyyy-MM-dd}로 strict parse한 뒤 Redis 가드 키용 {@code yyyyMMdd}로
     * 다시 변환한다.
     */
    private String periodKeyOf(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().format(PERIOD_KEY_FORMAT);
    }

    private String missionKeyOf(Mission mission, Long creatorId, String periodKey) {
        return "%s:%d:%s".formatted(mission.getType().name().toLowerCase(Locale.ROOT), creatorId, periodKey);
    }
}

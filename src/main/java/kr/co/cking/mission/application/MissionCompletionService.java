package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
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
 * Creator별 LIKE 미션 완료 처리 + 크리에이터별 EARN 연동(FR-P1-005~008, 014~016).
 * 공용 ATTENDANCE는 {@link CommonMissionCompletionService}가 담당한다.
 *
 * <p>{@code mission_completion} 저장·EARN Ledger·DB Balance 반영은 이 계층의 책임이
 * 아니다 — {@link TicketEarnService#earn}이 Redis Lua로 멱등성·중복 적립 가드·Balance
 * 증가·Stream 발행을 원자 처리하고, EARN Stream Consumer가 그 이후 비동기로
 * {@code mission_completion}/{@code ticket_ledger}/{@code user_ticket_balance}를 한
 * 트랜잭션에 반영한다(취합v1.5.4 §4.5). 이 API가 {@code mission_completion}을 먼저
 * 써버리면 Consumer가 재전달로 오판해 Ledger·Balance 반영을 건너뛰므로, 이 클래스는
 * Redis/Stream 계층(EARN 결과코드) 밖의 어떤 영속 상태도 직접 만들지 않는다.
 *
 * <p><b>일반 완료 유형은 기존 requestId 조회를 미션 활성 검증보다 먼저 한다(Issue
 * #125)</b>: LIKE 미션이 종료된 뒤 이미 성공했던 requestId가 재전송되면,
 * {@link TicketEarnService#findExisting}로 먼저 확인해 {@code MISSION_INACTIVE}가 아니라
 * 기존 성공 결과를 반환해야 한다(FR-P1-018). 레거시 Creator ATTENDANCE도 기존 성공
 * requestId replay만 보존한다. 이미지 인증이 필요한 YOUTUBE_SUBSCRIPTION은 Ticket EARN
 * 조회보다 먼저 별도 인증 경계로 차단한다.
 */
@Service
@RequiredArgsConstructor
public class MissionCompletionService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private final MemberRepository memberRepository;
    private final MissionRepository missionRepository;
    private final TicketEarnService ticketEarnService;
    private final Clock clock;

    /** Creator별 일반 미션을 일 단위 Business Key와 EARN 처리로 완료한다. */
    public MissionCompleteOutcome complete(Long creatorId, Long missionId, MissionCompleteCommand command) {
        memberRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Mission mission = missionRepository.findByMissionIdAndCreatorId(missionId, creatorId)
                .orElseThrow(() -> new BusinessException(MissionErrorCode.MISSION_NOT_FOUND));

        if (mission.getType() == MissionType.YOUTUBE_SUBSCRIPTION) {
            throw new BusinessException(MissionErrorCode.MISSION_REQUIRES_VERIFICATION);
        }
        // SHARE 보상은 Creator Space 공유 완료 처리(Issue #311)만 담당한다. 일반 완료
        // API가 이 경로를 통과하면 공유 행위 없이 보상을 받는 우회가 가능해진다.
        if (mission.getType() == MissionType.SHARE) {
            throw new BusinessException(MissionErrorCode.MISSION_NOT_FOUND);
        }

        Instant now = clock.instant();
        String periodKey = periodKeyOf(now);
        EarnCommand earnCommand = new EarnCommand(
                command.requestId(),
                command.userId(),
                creatorId,
                mission.getType().name(),
                missionId,
                periodKey,
                missionKeyOf(mission, creatorId),
                mission.getRewardAmount().longValue()
        );

        EarnLookupStatus lookupStatus = ticketEarnService.findExisting(earnCommand).status();
        if (lookupStatus == EarnLookupStatus.ALREADY_PROCESSED) {
            return outcomeOf(EarnResultCode.ALREADY_PROCESSED, missionId, mission, now);
        }
        if (lookupStatus != EarnLookupStatus.NOT_FOUND) {
            // NOT_FOUND(신규 요청)만 활성 검증으로 진행한다. 그 외 값은
            // MissionErrorCode.from()의 컴파일타임 전수 switch에 위임한다 — 이렇게 하면
            // EarnLookupStatus에 값이 추가됐을 때 여기가 아니라 from()이 컴파일 실패로
            // 즉시 알려준다(런타임 방어 분기보다 안전하다).
            throw new BusinessException(MissionErrorCode.from(lookupStatus));
        }

        // 출석은 크리에이터와 무관한 CommonMission 경로에서만 신규 완료한다.
        // 다만 이전 creator ATTENDANCE 경로에서 이미 성공한 requestId의 replay는
        // 위 findExisting() 결과를 그대로 반환해 기존 멱등성 계약을 보존한다.
        if (mission.getType() != MissionType.LIKE) {
            throw new BusinessException(MissionErrorCode.MISSION_NOT_FOUND);
        }

        if (!mission.isActiveAt(now)) {
            throw new BusinessException(MissionErrorCode.MISSION_INACTIVE);
        }

        EarnResult result = ticketEarnService.earn(earnCommand);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            return outcomeOf(result.code(), missionId, mission, now);
        }
        throw new BusinessException(MissionErrorCode.from(result.code()));
    }

    /**
     * {@code completedAt}은 이 응답을 만든 시각({@code now})이다 — {@code ALREADY_PROCESSED}
     * replay 경로에서는 최초로 실제 완료된 시각이 아니다. {@code EarnLookupResult}/{@code EarnResult}가
     * 원본 완료 시각을 담고 있지 않기 때문이다(System2 EARN Replay Contract §5, "resultCode만으로
     * 충분하며 향후 필요해지면 replay 값을 확장한다"). 원본 시각이 필요해지면 EARN 쪽에 그 값을
     * replay 레코드에 실어달라고 별도로 요청해야 한다.
     */
    private MissionCompleteOutcome outcomeOf(EarnResultCode code, Long missionId, Mission mission, Instant now) {
        return new MissionCompleteOutcome(code, missionId, mission.getRewardAmount(), now);
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

    /**
     * {@code periodKey}를 포함하지 않는다 — {@link TicketEarnService}의 fingerprint
     * 계산이 이 값을 담으므로, 여기에 날짜가 들어가면 자정 이후 동일 requestId 재시도가
     * 다른 fingerprint로 판정돼 {@code ALREADY_PROCESSED} 대신 {@code REQUEST_ID_CONFLICT}가
     * 반환된다(periodKey는 이미 별도 필드로 Guard 키·Stream에 전달된다).
     */
    private String missionKeyOf(Mission mission, Long creatorId) {
        return "%s:%d".formatted(mission.getType().name().toLowerCase(Locale.ROOT), creatorId);
    }
}

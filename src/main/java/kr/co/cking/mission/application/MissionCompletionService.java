package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.Mission;
import kr.co.cking.mission.domain.MissionCompletion;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.repository.MissionCompletionRepository;
import kr.co.cking.mission.repository.MissionRepository;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 미션 완료 처리 + EARN 연동(FR-P1-005~008, 014~016).
 *
 * <p>중복 방지는 두 레이어로 나뉜다 — 이 클래스는 {@code mission_completion}의 DB UNIQUE
 * 제약({@code uk_completion_business}, {@code uk_completion_request})까지만 책임진다.
 * Redis 쪽 가드(《mission:earn-guard:...》, requestId/fingerprint 비교 포함)는
 * {@code ticket-earn.lua}(이슈 #30, PR #63)가 이미 원자적으로 처리하므로 이 클래스가
 * 같은 키를 다시 건드리지 않는다 — 예전에 관측용으로 {@code MissionEarnGuard}를 여기서
 * 같은 키에 SETNX 했다가, Lua의 자체 판정과 충돌해 정상 요청까지 DUPLICATE_MISSION으로
 * 오판되는 버그가 있었다(제거됨, PR #78 리뷰).
 *
 *
 * <p><b>알려진 제약(미해결)</b>: {@code earn()}이 이슈 #30(Business Key 가드) 완료 전까지
 * 멱등하지 않아서(같은 커맨드를 두 번 부르면 잔액이 두 번 오른다 — {@code TicketEarnServiceImplTest}의
 * "여러번 적립하면 잔액이 누적된다" 참고), {@code mission_completion} 삽입 성공 이후에만
 * {@code earn()}을 호출한다. 따라서 완료 기록은 남았지만 {@code earn()} 호출이 실패한 뒤 같은 requestId로
 * 재시도하면 {@code earn()}을 다시 부르지 않고 {@code ALREADY_PROCESSED}를 반환한다 —
 * 티켓을 못 받은 사용자가 재시도로 못 받는 상태다. 근본 해법은 {@code mission_completion}에
 * {@code earn_status}(PENDING/CONFIRMED/FAILED) 컬럼을 추가해 PENDING·FAILED일 때
 * {@code earn()}을 재시도하도록 바꾸는 것이며, 스키마 변경이 필요해 별도로 처리한다.
 * 그 전까지는 Redis·DB 정합성 배치(T2-06)의 COMPENSATE 보정이 안전망이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionCompletionService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final MemberRepository memberRepository;
    private final MissionRepository missionRepository;
    private final MissionCompletionRepository missionCompletionRepository;
    private final MissionCompletionRecorder missionCompletionRecorder;
    private final TicketEarnService ticketEarnService;
    private final Clock clock;

    /**
     * 의도적으로 {@code @Transactional}을 두르지 않는다. INSERT 시도는
     * {@link MissionCompletionRecorder}가 별도 트랜잭션으로 격리하고, {@code earn()}은
     * Redis를 호출하는 외부 I/O라 DB 트랜잭션을 그 시간만큼 붙잡아 둘 이유가 없다.
     * 조회 각각은 Spring Data가 자체 트랜잭션으로 처리한다.
     */
    public MissionCompleteOutcome complete(Long creatorId, Long missionId, MissionCompleteCommand command) {
        memberRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Mission mission = missionRepository.findByMissionIdAndCreatorId(missionId, creatorId)
                .orElseThrow(() -> new BusinessException(MissionErrorCode.MISSION_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);
        if (!mission.isActiveAt(now)) {
            throw new BusinessException(MissionErrorCode.MISSION_INACTIVE);
        }

        String requestId = command.requestId().toString();
        String periodKey = periodKeyOf(now);

        // 1) 동일 requestId 재전송 — 기존 결과 반환(네트워크 재시도 멱등성, FR-P1-016~017)
        //    단, 같은 requestId에 다른 내용(userId/creatorId/missionId)이 들어오면
        //    REQUEST_ID_CONFLICT로 구분한다(취합v1.5.4 §4.4).
        Optional<MissionCompletion> existing = missionCompletionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            MissionCompletion found = existing.get();
            if (!isSamePayload(found, command.userId(), creatorId, missionId)) {
                throw new BusinessException(MissionErrorCode.REQUEST_ID_CONFLICT);
            }
            log.info("mission completion replay: requestId={}", requestId);
            return new MissionCompleteOutcome(EarnResultCode.ALREADY_PROCESSED, missionId,
                    mission.getRewardAmount(), found.getCompletedAt());
        }

        // 2) 새 requestId + 동일 Business Key — 같은 기간에 이미 완료됨(FR-P1-014~015)
        boolean businessDuplicate = missionCompletionRepository
                .existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                        command.userId(), creatorId, missionId, periodKey);
        if (businessDuplicate) {
            throw new BusinessException(MissionErrorCode.DUPLICATE_MISSION);
        }

        try {
            missionCompletionRecorder.tryInsert(
                    command.userId(), creatorId, missionId, periodKey, command.requestId(), now);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청 경쟁: 두 요청 중 하나만 UNIQUE 제약을 통과했다. Recorder의 REQUIRES_NEW
            // 트랜잭션은 이미 자기 안에서 롤백되고 끝났으므로(예외가 그 경계 밖으로 전파됨),
            // 여기서 하는 조회는 깨끗한 상태에서 시작한다. 이 catch는 이 메서드가
            // @Transactional이 아니기 때문에 안전하다 — 감쌀 트랜잭션 자체가 없다.
            return resolveConcurrentInsertFailure(command.userId(), creatorId, missionId, periodKey, requestId, mission);
        }

        return earn(mission, command.userId(), creatorId, missionId, periodKey, command.requestId(), now);
    }

    private MissionCompleteOutcome resolveConcurrentInsertFailure(
            Long userId, Long creatorId, Long missionId, String periodKey, String requestId, Mission mission) {
        Optional<MissionCompletion> byRequestId = missionCompletionRepository.findByRequestId(requestId);
        if (byRequestId.isPresent()) {
            MissionCompletion found = byRequestId.get();
            if (!isSamePayload(found, userId, creatorId, missionId)) {
                throw new BusinessException(MissionErrorCode.REQUEST_ID_CONFLICT);
            }
            return new MissionCompleteOutcome(EarnResultCode.ALREADY_PROCESSED, missionId,
                    mission.getRewardAmount(), found.getCompletedAt());
        }
        boolean businessDuplicate = missionCompletionRepository
                .existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKeyAndRequestIdNot(
                        userId, creatorId, missionId, periodKey, requestId);
        if (businessDuplicate) {
            throw new BusinessException(MissionErrorCode.DUPLICATE_MISSION);
        }
        // UNIQUE 제약이 있는 이상 둘 중 하나는 반드시 해당해야 한다 — 도달하면 원인 불명 상태다.
        throw new BusinessException(MissionErrorCode.EARN_STATUS_UNKNOWN);
    }

    /** 저장된 완료 기록이 이번 요청과 같은 대상(사용자·크리에이터·미션)을 가리키는지 확인한다. */
    private boolean isSamePayload(MissionCompletion found, Long userId, Long creatorId, Long missionId) {
        return found.getMemberId().equals(userId)
                && found.getCreatorId().equals(creatorId)
                && found.getMissionId().equals(missionId);
    }

    /**
     * 완료 기록이 확정된 뒤에만 호출한다. {@code earn()} 실패 시 이미 남긴
     * {@code mission_completion} 행은 그대로 두고(클래스 상단 "알려진 제약" 참고)
     * {@link MissionErrorCode}로 변환해 예외로 던진다.
     *
     * @param completedAt {@code mission_completion}에 실제로 저장한 시각. {@code earn()}
     *                    호출(Redis 왕복)이 끝난 뒤 시계를 다시 찍지 않고 이 값을 그대로 응답에 싣는다 —
     *                    안 그러면 같은 완료 건인데 EARN_ACCEPTED 응답과 이후 ALREADY_PROCESSED
     *                    응답(DB에서 읽은 값)의 completedAt이 미묘하게 어긋난다.
     */
    private MissionCompleteOutcome earn(Mission mission, Long userId, Long creatorId, Long missionId,
                                         String periodKey, UUID requestId, LocalDateTime completedAt) {
        EarnCommand earnCommand = new EarnCommand(
                requestId,
                userId,
                creatorId,
                mission.getType().name(),
                missionId,
                periodKey,
                missionKeyOf(mission, creatorId, periodKey),
                mission.getRewardAmount().longValue()
        );

        EarnResult result = ticketEarnService.earn(earnCommand);
        if (result.code() == EarnResultCode.EARN_ACCEPTED || result.code() == EarnResultCode.ALREADY_PROCESSED) {
            return new MissionCompleteOutcome(result.code(), missionId, mission.getRewardAmount(), completedAt);
        }
        throw new BusinessException(MissionErrorCode.from(result.code()));
    }

    private String periodKeyOf(LocalDateTime now) {
        // Clock이 UTC 기준이므로 now를 그대로 날짜만 취한다(팀 합의: periodKey는 UTC 기준).
        return now.toLocalDate().format(PERIOD_KEY_FORMAT);
    }

    private String missionKeyOf(Mission mission, Long creatorId, String periodKey) {
        return "%s:%d:%s".formatted(mission.getType().name().toLowerCase(Locale.ROOT), creatorId, periodKey);
    }
}

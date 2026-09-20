package kr.co.cking.mission;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionCompletionServiceTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final TicketEarnService ticketEarnService = mock(TicketEarnService.class);

    private static final Long USER_ID = 1L;
    private static final Long CREATOR_ID = 10L;
    private static final Long MISSION_ID = 100L;

    private MissionCompletionService serviceWith(Clock clock) {
        return new MissionCompletionService(memberRepository, missionRepository, ticketEarnService, clock);
    }

    private Mission attendanceMission() {
        return new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1, null, null);
    }

    private void stubMemberAndMission(Mission mission) {
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(mock(Member.class)));
        when(missionRepository.findByMissionIdAndCreatorId(MISSION_ID, CREATOR_ID))
                .thenReturn(Optional.of(mission));
    }

    /** 대부분의 테스트는 "기존 요청 없음"을 전제로 하므로 기본값으로 묶어둔다. */
    private void stubNoExistingReplay() {
        when(ticketEarnService.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
    }

    @Test
    void 출석_미션을_최초_완료하면_EARN_ACCEPTED를_반환한다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        MissionCompleteOutcome outcome = serviceWith(clock).complete(
                CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(outcome.rewardAmount()).isEqualTo(1);
    }

    @Test
    void periodKey는_UTC_날짜를_그대로_사용한다() {
        // now=2026-09-16T23:30:00Z: KST로 환산하면 다음날 08:30(9/17)이지만,
        // RTM FR-P1-006/FR-P1-021/FR-P2-006(UTC 확정, PR #63 리뷰)에 따라
        // periodKey는 변환 없이 UTC 날짜인 2026-09-16이어야 한다.
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T23:30:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        serviceWith(clock).complete(CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        var captor = org.mockito.ArgumentCaptor.forClass(EarnCommand.class);
        verify(ticketEarnService).earn(captor.capture());
        assertThat(captor.getValue().periodKey()).isEqualTo("2026-09-16");
    }

    @Test
    void missionKey는_periodKey와_달리_날짜에_독립적이다() {
        // TicketEarnService의 fingerprint 계산은 periodKey를 명시적으로 제외하지만
        // missionKey는 포함한다. missionKey에 날짜가 섞여 있으면 자정을 넘겨 동일
        // requestId를 재시도할 때 fingerprint가 달라져 ALREADY_PROCESSED 대신
        // REQUEST_ID_CONFLICT가 반환된다 — 그래서 missionKey는 periodKey가 바뀌어도
        // 항상 같아야 한다.
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-09-16T23:59:59Z"), ZoneOffset.UTC);
        serviceWith(beforeMidnight).complete(
                CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        Clock afterMidnight = Clock.fixed(Instant.parse("2026-09-17T00:00:01Z"), ZoneOffset.UTC);
        serviceWith(afterMidnight).complete(
                CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        var captor = org.mockito.ArgumentCaptor.forClass(EarnCommand.class);
        verify(ticketEarnService, org.mockito.Mockito.times(2)).earn(captor.capture());
        var commands = captor.getAllValues();
        assertThat(commands.get(0).periodKey()).isEqualTo("2026-09-16");
        assertThat(commands.get(1).periodKey()).isEqualTo("2026-09-17");
        assertThat(commands.get(0).missionKey()).isEqualTo(commands.get(1).missionKey());
    }

    @Test
    void 존재하지_않는_사용자는_RESOURCE_NOT_FOUND다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(ticketEarnService, never()).findExisting(any());
        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 존재하지_않는_미션은_MISSION_NOT_FOUND다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(mock(Member.class)));
        when(missionRepository.findByMissionIdAndCreatorId(MISSION_ID, CREATOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_NOT_FOUND);
    }

    @Test
    void 활성_기간이_지난_미션은_기존_요청이_없으면_MISSION_INACTIVE다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        Mission expired = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"));
        stubMemberAndMission(expired);
        stubNoExistingReplay();

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_INACTIVE);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 종료된_미션에_동일_requestId로_재시도하면_ALREADY_PROCESSED를_반환한다() {
        // Issue #125 재현 시나리오: 활성 상태에서 성공한 requestId를 미션 종료 후
        // 재전송해도 MISSION_INACTIVE가 아니라 기존 성공 결과를 반환해야 한다(FR-P1-018).
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        Mission expired = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"));
        stubMemberAndMission(expired);
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED));

        MissionCompleteOutcome outcome = serviceWith(clock).complete(
                CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 종료된_미션에_다른_payload로_같은_requestId가_오면_REQUEST_ID_CONFLICT다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        Mission expired = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"));
        stubMemberAndMission(expired);
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT));

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.REQUEST_ID_CONFLICT);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 기존_요청_조회가_UNAVAILABLE이면_활성_상태와_무관하게_시스템_오류다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.UNAVAILABLE));

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.EARN_STATUS_UNKNOWN);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 종료된_미션도_UNAVAILABLE이면_MISSION_INACTIVE가_아니라_시스템_오류다() {
        // "활성 상태와 무관하게"라는 이름값을 실제로 비활성 미션으로도 검증한다 —
        // 활성 검증이 UNAVAILABLE보다 먼저 오도록 실수로 순서가 바뀌는 회귀를 잡는다.
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        Mission expired = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"));
        stubMemberAndMission(expired);
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.UNAVAILABLE));

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.EARN_STATUS_UNKNOWN);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void activeTo와_정확히_같은_시각은_비활성이다() {
        // 계약: activeFrom <= now < activeTo (Event의 startAt<=now<endAt과 동일 원칙).
        // now == activeTo인 경계는 활성이 아니어야 한다.
        Instant activeTo = Instant.parse("2026-09-16T00:00:00Z");
        Clock clock = Clock.fixed(activeTo, ZoneOffset.UTC);
        Mission mission = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                Instant.parse("2026-09-01T00:00:00Z"), activeTo);
        stubMemberAndMission(mission);
        stubNoExistingReplay();

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_INACTIVE);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void activeTo_직전_시각은_활성이다() {
        Instant activeTo = Instant.parse("2026-09-16T00:00:00Z");
        Clock clock = Clock.fixed(activeTo.minusMillis(1), ZoneOffset.UTC);
        Mission mission = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                Instant.parse("2026-09-01T00:00:00Z"), activeTo);
        stubMemberAndMission(mission);
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        MissionCompleteOutcome outcome = serviceWith(clock).complete(
                CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
    }

    @Test
    void 동일_requestId_재요청은_earn을_통해서도_ALREADY_PROCESSED를_반환한다() {
        // findExisting()이 NOT_FOUND였다가(예: idem 캐시가 막 만료), 실제 earn() 호출에서
        // Lua가 가드로 재확인해 ALREADY_PROCESSED를 반환하는 경로도 그대로 성공 처리해야 한다.
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.ALREADY_PROCESSED));

        MissionCompleteOutcome outcome = serviceWith(clock).complete(
                CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
    }

    @ParameterizedTest
    @EnumSource(value = EarnResultCode.class, names = {
            "DUPLICATE_MISSION", "REQUEST_ID_CONFLICT", "EARN_PROCESSING_FAILED", "EARN_STATUS_UNKNOWN"
    })
    void EARN_실패_결과코드는_대응하는_MissionErrorCode_예외로_변환된다(EarnResultCode code) {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(code));

        assertThatThrownBy(() -> serviceWith(clock).complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.from(code));
    }
}

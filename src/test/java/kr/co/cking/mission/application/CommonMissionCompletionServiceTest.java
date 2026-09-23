package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.CommonMission;
import kr.co.cking.mission.CommonMissionRepository;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.ticket.application.CommonTicketEarnService;
import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonMissionCompletionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long MISSION_ID = 100L;

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CommonMissionRepository missionRepository = mock(CommonMissionRepository.class);
    private final CommonTicketEarnService ticketEarnService = mock(CommonTicketEarnService.class);

    private CommonMissionCompletionService serviceWith(Clock clock) {
        return new CommonMissionCompletionService(memberRepository, missionRepository, ticketEarnService, clock);
    }

    private CommonMission attendanceMission() {
        return new CommonMission(CommonMissionType.ATTENDANCE, 1, null, null);
    }

    private void stubMemberAndMission(CommonMission mission) {
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(mock(Member.class)));
        when(missionRepository.findByMissionId(MISSION_ID)).thenReturn(Optional.of(mission));
    }

    private void stubNoExistingReplay() {
        when(ticketEarnService.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
    }

    @Test
    void 공용_미션을_최초_완료하면_EARN_ACCEPTED를_반환한다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        MissionCompleteOutcome outcome = serviceWith(clock).complete(
                MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(outcome.rewardAmount()).isEqualTo(1);
    }

    @Test
    void periodKey는_UTC_날짜를_그대로_사용한다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T23:30:00Z"), ZoneOffset.UTC);
        stubMemberAndMission(attendanceMission());
        stubNoExistingReplay();
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        serviceWith(clock).complete(MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        var captor = org.mockito.ArgumentCaptor.forClass(CommonEarnCommand.class);
        verify(ticketEarnService).earn(captor.capture());
        assertThat(captor.getValue().periodKey()).isEqualTo("2026-09-16");
    }

    @Test
    void 존재하지_않는_사용자는_RESOURCE_NOT_FOUND다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWith(clock).complete(MISSION_ID,
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
        when(missionRepository.findByMissionId(MISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWith(clock).complete(MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_NOT_FOUND);
    }

    @Test
    void 활성_기간이_지난_미션은_기존_요청이_없으면_MISSION_INACTIVE다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        CommonMission expired = new CommonMission(CommonMissionType.ATTENDANCE, 1,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"));
        stubMemberAndMission(expired);
        stubNoExistingReplay();

        assertThatThrownBy(() -> serviceWith(clock).complete(MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_INACTIVE);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 종료된_미션에_동일_requestId로_재시도하면_ALREADY_PROCESSED를_반환한다() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
        CommonMission expired = new CommonMission(CommonMissionType.ATTENDANCE, 1,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"));
        stubMemberAndMission(expired);
        when(ticketEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED));

        MissionCompleteOutcome outcome = serviceWith(clock).complete(
                MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 활성_경계는_크리에이터별_미션과_동일하다_activeTo는_비활성() {
        Instant activeTo = Instant.parse("2026-09-16T00:00:00Z");
        Clock clock = Clock.fixed(activeTo, ZoneOffset.UTC);
        CommonMission mission = new CommonMission(CommonMissionType.ATTENDANCE, 1,
                Instant.parse("2026-09-01T00:00:00Z"), activeTo);
        stubMemberAndMission(mission);
        stubNoExistingReplay();

        assertThatThrownBy(() -> serviceWith(clock).complete(MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_INACTIVE);
    }
}

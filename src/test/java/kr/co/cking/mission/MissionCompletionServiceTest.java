package kr.co.cking.mission;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.MissionCompletionRecorder;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.Mission;
import kr.co.cking.mission.domain.MissionCompletion;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.mission.repository.MissionCompletionRepository;
import kr.co.cking.mission.repository.MissionRepository;
import kr.co.cking.ticket.application.TicketEarnService;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionCompletionServiceTest {

    // periodKey는 UTC 기준(팀 합의) — 이 인스턴트의 UTC 날짜는 2026-09-16.
    private final Instant now = Instant.parse("2026-09-16T01:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final MissionCompletionRepository missionCompletionRepository = mock(MissionCompletionRepository.class);
    private final MissionCompletionRecorder missionCompletionRecorder = mock(MissionCompletionRecorder.class);
    private final TicketEarnService ticketEarnService = mock(TicketEarnService.class);

    private final MissionCompletionService service = new MissionCompletionService(
            memberRepository, missionRepository, missionCompletionRepository, missionCompletionRecorder,
            ticketEarnService, clock);

    private static final Long USER_ID = 1L;
    private static final Long CREATOR_ID = 10L;
    private static final Long MISSION_ID = 100L;

    private Mission attendanceMission() {
        return new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1, null, null);
    }

    private void stubMemberAndMission(Mission mission) {
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(mock(Member.class)));
        when(missionRepository.findByMissionIdAndCreatorId(MISSION_ID, CREATOR_ID))
                .thenReturn(Optional.of(mission));
    }

    @Test
    void 출석_미션을_최초_완료하면_EARN_ACCEPTED를_반환한다() {
        stubMemberAndMission(attendanceMission());
        when(missionCompletionRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(missionCompletionRepository.existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                USER_ID, CREATOR_ID, MISSION_ID, "2026-09-16")).thenReturn(false);
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        MissionCompleteCommand command = new MissionCompleteCommand(USER_ID, UUID.randomUUID());
        MissionCompleteOutcome outcome = service.complete(CREATOR_ID, MISSION_ID, command);

        assertThat(outcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(outcome.rewardAmount()).isEqualTo(1);
        verify(missionCompletionRecorder).tryInsert(
                eq(USER_ID), eq(CREATOR_ID), eq(MISSION_ID), eq("2026-09-16"), any(), any());
    }

    @Test
    void periodKey는_UTC_날짜를_그대로_사용한다() {
        // now=2026-09-16T23:30:00Z: KST로 환산하면 다음날 08:30(9/17)이지만,
        // 팀 합의(UTC 기준)에 따라 periodKey는 변환 없이 UTC 날짜인 2026-09-16이어야 한다.
        Instant lateUtc = Instant.parse("2026-09-16T23:30:00Z");
        Clock lateClock = Clock.fixed(lateUtc, ZoneOffset.UTC);
        MissionCompletionService lateService = new MissionCompletionService(
                memberRepository, missionRepository, missionCompletionRepository, missionCompletionRecorder,
                ticketEarnService, lateClock);
        stubMemberAndMission(attendanceMission());
        when(missionCompletionRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        lateService.complete(CREATOR_ID, MISSION_ID, new MissionCompleteCommand(USER_ID, UUID.randomUUID()));

        verify(missionCompletionRepository).existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                USER_ID, CREATOR_ID, MISSION_ID, "2026-09-16");
    }

    @Test
    void 존재하지_않는_사용자는_RESOURCE_NOT_FOUND다() {
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 존재하지_않는_미션은_MISSION_NOT_FOUND다() {
        when(memberRepository.findById(USER_ID)).thenReturn(Optional.of(mock(Member.class)));
        when(missionRepository.findByMissionIdAndCreatorId(MISSION_ID, CREATOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_NOT_FOUND);
    }

    @Test
    void 활성_기간이_지난_미션은_MISSION_INACTIVE다() {
        Mission expired = new Mission(CREATOR_ID, MissionType.ATTENDANCE, 1,
                LocalDateTime.parse("2020-01-01T00:00:00"), LocalDateTime.parse("2020-01-31T00:00:00"));
        stubMemberAndMission(expired);

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_INACTIVE);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 동일_requestId_재요청은_earn을_다시_호출하지_않고_ALREADY_PROCESSED를_반환한다() {
        stubMemberAndMission(attendanceMission());
        UUID requestId = UUID.randomUUID();
        LocalDateTime completedAt = LocalDateTime.now(clock);
        MissionCompletion existing = new MissionCompletion(
                USER_ID, CREATOR_ID, MISSION_ID, "2026-09-16", requestId, completedAt);
        when(missionCompletionRepository.findByRequestId(requestId.toString())).thenReturn(Optional.of(existing));

        MissionCompleteOutcome outcome = service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, requestId));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(ticketEarnService, never()).earn(any());
        verify(missionCompletionRecorder, never()).tryInsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 동일_requestId에_다른_creatorId가_들어오면_REQUEST_ID_CONFLICT다() {
        stubMemberAndMission(attendanceMission());
        UUID requestId = UUID.randomUUID();
        MissionCompletion existing = new MissionCompletion(
                USER_ID, 999L, MISSION_ID, "2026-09-16", requestId, LocalDateTime.now(clock));
        when(missionCompletionRepository.findByRequestId(requestId.toString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, requestId)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.REQUEST_ID_CONFLICT);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 새_requestId로_같은_기간에_이미_완료된_미션이면_DUPLICATE_MISSION이다() {
        stubMemberAndMission(attendanceMission());
        when(missionCompletionRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(missionCompletionRepository.existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                USER_ID, CREATOR_ID, MISSION_ID, "2026-09-16")).thenReturn(true);

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.DUPLICATE_MISSION);

        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void EARN_처리가_실패하면_EARN_PROCESSING_FAILED_예외를_던진다() {
        stubMemberAndMission(attendanceMission());
        when(missionCompletionRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(missionCompletionRepository.existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                any(), any(), any(), any())).thenReturn(false);
        when(ticketEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_PROCESSING_FAILED));

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.EARN_PROCESSING_FAILED);
    }

    @Test
    void 동시_요청_경쟁으로_삽입이_실패하면_기존_requestId_결과를_ALREADY_PROCESSED로_반환한다() {
        stubMemberAndMission(attendanceMission());
        UUID requestId = UUID.randomUUID();
        when(missionCompletionRepository.findByRequestId(requestId.toString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new MissionCompletion(
                        USER_ID, CREATOR_ID, MISSION_ID, "2026-09-16", requestId, LocalDateTime.now(clock))));
        when(missionCompletionRepository.existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                any(), any(), any(), any())).thenReturn(false);
        doThrow(new DataIntegrityViolationException("unique violation"))
                .when(missionCompletionRecorder).tryInsert(any(), any(), any(), any(), any(), any());

        MissionCompleteOutcome outcome = service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, requestId));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(ticketEarnService, never()).earn(any());
    }

    @Test
    void 동시_요청_경쟁에서_business_key가_먼저_점유됐으면_DUPLICATE_MISSION이다() {
        stubMemberAndMission(attendanceMission());
        UUID requestId = UUID.randomUUID();
        when(missionCompletionRepository.findByRequestId(requestId.toString())).thenReturn(Optional.empty());
        when(missionCompletionRepository.existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
                any(), any(), any(), any())).thenReturn(false);
        doThrow(new DataIntegrityViolationException("unique violation"))
                .when(missionCompletionRecorder).tryInsert(any(), any(), any(), any(), any(), any());
        when(missionCompletionRepository.existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKeyAndRequestIdNot(
                USER_ID, CREATOR_ID, MISSION_ID, "2026-09-16", requestId.toString())).thenReturn(true);

        assertThatThrownBy(() -> service.complete(CREATOR_ID, MISSION_ID,
                new MissionCompleteCommand(USER_ID, requestId)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.DUPLICATE_MISSION);
    }
}

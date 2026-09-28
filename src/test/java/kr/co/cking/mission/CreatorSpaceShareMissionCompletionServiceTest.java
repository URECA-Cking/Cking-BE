package kr.co.cking.mission;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.CreatorSpaceShareMissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.TicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

class CreatorSpaceShareMissionCompletionServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long CREATOR_ID = 10L;
    private static final Long MISSION_ID = 100L;

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final TicketOnceEarnService ticketOnceEarnService = mock(TicketOnceEarnService.class);

    /** 고정 시각을 적용한 공유 미션 완료 서비스를 만든다. */
    private CreatorSpaceShareMissionCompletionService serviceWith(Clock clock) {
        return new CreatorSpaceShareMissionCompletionService(
                memberRepository, creatorRepository, missionRepository, ticketOnceEarnService, clock);
    }

    /** 공유 대상 Creator에 속한 기본 SHARE 미션을 만든다. */
    private Mission shareMission() {
        Mission mission = new Mission(CREATOR_ID, MissionType.SHARE, 1, null, null);
        ReflectionTestUtils.setField(mission, "missionId", MISSION_ID);
        return mission;
    }

    /** 정상 공유 완료에 필요한 Member·Creator·SHARE 미션 조회를 준비한다. */
    private void stubShareMission(Mission mission) {
        when(memberRepository.existsById(MEMBER_ID)).thenReturn(true);
        when(creatorRepository.existsById(CREATOR_ID)).thenReturn(true);
        when(missionRepository.findByCreatorIdAndType(CREATOR_ID, MissionType.SHARE)).thenReturn(Optional.of(mission));
    }

    /** 신규 EARN 요청이 되도록 기존 requestId가 없음을 준비한다. */
    private void stubNoExistingReplay() {
        when(ticketOnceEarnService.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
    }

    /** Creator Space 공유는 SHARE 유형의 Creator 전용 EARN을 요청한다. */
    @Test
    void 공유를_최초_완료하면_SHARE_EARN_ACCEPTED를_반환한다() {
        stubShareMission(shareMission());
        stubNoExistingReplay();
        when(ticketOnceEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        MissionCompleteOutcome outcome = serviceWith(Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC))
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        var captor = org.mockito.ArgumentCaptor.forClass(EarnCommand.class);
        verify(ticketOnceEarnService).earn(captor.capture());
        assertThat(captor.getValue())
                .extracting(EarnCommand::creatorId, EarnCommand::missionId, EarnCommand::missionType,
                        EarnCommand::missionKey, EarnCommand::amount)
                .containsExactly(CREATOR_ID, MISSION_ID, "SHARE", "share:10", 1L);
    }

    /** 서버 UTC 날짜가 한국 시간과 달라도 SHARE Business Key에 그대로 쓰이는지 확인한다. */
    @Test
    void periodKey는_서버_UTC_날짜를_사용한다() {
        stubShareMission(shareMission());
        stubNoExistingReplay();
        when(ticketOnceEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        serviceWith(Clock.fixed(Instant.parse("2026-09-16T23:30:00Z"), ZoneOffset.UTC))
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID()));

        var captor = org.mockito.ArgumentCaptor.forClass(EarnCommand.class);
        verify(ticketOnceEarnService).earn(captor.capture());
        assertThat(captor.getValue().periodKey()).isEqualTo("2026-09-16");
    }

    /** 같은 requestId의 공유 재시도는 새 적립 없이 기존 성공 결과를 반환한다. */
    @Test
    void 같은_requestId_재요청은_ALREADY_PROCESSED를_반환한다() {
        stubShareMission(shareMission());
        when(ticketOnceEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED));

        MissionCompleteOutcome outcome = serviceWith(Clock.systemUTC())
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID()));

        assertThat(outcome.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(ticketOnceEarnService, never()).earn(any());
    }

    /** 같은 날 다른 requestId의 재공유는 Ticket EARN의 Business Key 가드로 차단한다. */
    @Test
    void 동일_Business_Key_재수행은_DUPLICATE_MISSION이다() {
        stubShareMission(shareMission());
        stubNoExistingReplay();
        when(ticketOnceEarnService.earn(any())).thenReturn(new EarnResult(EarnResultCode.DUPLICATE_MISSION));

        assertThatThrownBy(() -> serviceWith(Clock.systemUTC())
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.DUPLICATE_MISSION);
    }

    /** 없는 공유 대상 Creator는 미션 조회나 EARN 전에 리소스 없음으로 차단한다. */
    @Test
    void 존재하지_않는_Creator는_RESOURCE_NOT_FOUND다() {
        when(memberRepository.existsById(MEMBER_ID)).thenReturn(true);
        when(creatorRepository.existsById(CREATOR_ID)).thenReturn(false);

        assertThatThrownBy(() -> serviceWith(Clock.systemUTC())
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);

        verify(ticketOnceEarnService, never()).findExisting(any());
        verify(ticketOnceEarnService, never()).earn(any());
    }

    /** 대상 Creator에게 SHARE 미션이 없으면 보상을 지급하지 않는다. */
    @Test
    void SHARE_미션이_없으면_MISSION_NOT_FOUND다() {
        when(memberRepository.existsById(MEMBER_ID)).thenReturn(true);
        when(creatorRepository.existsById(CREATOR_ID)).thenReturn(true);
        when(missionRepository.findByCreatorIdAndType(CREATOR_ID, MissionType.SHARE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWith(Clock.systemUTC())
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_NOT_FOUND);
    }

    /** 비활성 SHARE 미션은 신규 공유 보상을 적립하지 않고 활성 상태 오류를 반환한다. */
    @Test
    void 비활성_SHARE_미션은_MISSION_INACTIVE다() {
        Mission expiredShareMission = new Mission(CREATOR_ID, MissionType.SHARE, 1,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-16T00:00:00Z"));
        stubShareMission(expiredShareMission);
        stubNoExistingReplay();

        assertThatThrownBy(() -> serviceWith(Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC))
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_INACTIVE);

        verify(ticketOnceEarnService, never()).earn(any());
    }

    /** 같은 requestId에 다른 공유 요청이 감지되면 EARN 조회 결과를 충돌 오류로 변환한다. */
    @Test
    void 다른_요청의_같은_requestId는_REQUEST_ID_CONFLICT다() {
        stubShareMission(shareMission());
        when(ticketOnceEarnService.findExisting(any()))
                .thenReturn(new EarnLookupResult(EarnLookupStatus.REQUEST_ID_CONFLICT));

        assertThatThrownBy(() -> serviceWith(Clock.systemUTC())
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.REQUEST_ID_CONFLICT);

        verify(ticketOnceEarnService, never()).earn(any());
    }

    /** 조회 결과가 요청 Creator 소속이 아니면 보상 경계를 넘지 못하게 한다. */
    @Test
    void SHARE_미션의_creatorId가_다르면_MISSION_NOT_FOUND다() {
        Mission otherCreatorMission = new Mission(20L, MissionType.SHARE, 1, null, null);
        stubShareMission(otherCreatorMission);

        assertThatThrownBy(() -> serviceWith(Clock.systemUTC())
                .complete(CREATOR_ID, new MissionCompleteCommand(MEMBER_ID, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MissionErrorCode.MISSION_NOT_FOUND);

        verify(ticketOnceEarnService, never()).findExisting(any());
        verify(ticketOnceEarnService, never()).earn(any());
    }
}

package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketOnceEarnServiceImplTest {

    private final TicketOnceEarnRequestClaimService claimService = mock(TicketOnceEarnRequestClaimService.class);
    private final TicketEarnRequestClaimService requestClaimService = mock(TicketEarnRequestClaimService.class);
    private final TicketEarnClaimedExecutor claimedExecutor = mock(TicketEarnClaimedExecutor.class);
    private final TicketOnceEarnService service = new TicketOnceEarnServiceImpl(
            claimService, requestClaimService, claimedExecutor);

    @BeforeEach
    void setUp() {
        when(requestClaimService.claim(any(EarnCommand.class))).thenReturn(TicketEarnRequestClaim.PENDING);
    }

    @Test
    void 이미_ACCEPTED인_durable_요청은_Redis를_다시_실행하지_않는다() {
        EarnCommand command = onceCommand();
        when(claimService.claim(any(), any())).thenReturn(TicketOnceEarnRequestClaim.ACCEPTED);

        EarnResult result = service.earn(command);

        assertThat(result.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(claimedExecutor, never()).earnClaimed(any());
    }

    @Test
    void 다른_requestId의_동일_평생_업무키는_중복으로_차단한다() {
        when(claimService.claim(any(), any())).thenReturn(TicketOnceEarnRequestClaim.DUPLICATE);

        EarnResult result = service.earn(onceCommand());

        assertThat(result.code()).isEqualTo(EarnResultCode.DUPLICATE_MISSION);
        verify(claimedExecutor, never()).earnClaimed(any());
    }

    @Test
    void DAILY에서_이미_사용한_requestId는_ONCE_durable_선점_전에_차단한다() {
        when(requestClaimService.claim(any(EarnCommand.class))).thenReturn(TicketEarnRequestClaim.REQUEST_ID_CONFLICT);

        EarnResult result = service.earn(onceCommand());

        assertThat(result.code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
        verify(claimService, never()).claim(any(), any());
        verify(claimedExecutor, never()).earnClaimed(any());
    }

    @Test
    void 전역_claim이_ACCEPTED이고_ONCE_claim이_PENDING이면_ONCE를_복구한다() {
        EarnCommand command = onceCommand();
        when(requestClaimService.claim(command)).thenReturn(TicketEarnRequestClaim.ACCEPTED);
        when(claimService.find(command.requestId().toString(), command.computeFingerprint()))
                .thenReturn(TicketOnceEarnRequestClaim.PENDING);

        EarnResult result = service.earn(command);

        assertThat(result.code()).isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(claimService).accept(command.requestId().toString());
        verify(claimedExecutor, never()).earnClaimed(any());
    }

    @Test
    void Redis가_수락하면_durable_요청을_ACCEPTED로_확정한다() {
        EarnCommand command = onceCommand();
        when(claimService.claim(any(), any())).thenReturn(TicketOnceEarnRequestClaim.PENDING);
        when(claimedExecutor.earnClaimed(command)).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));

        EarnResult result = service.earn(command);

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        verify(requestClaimService).claim(command);
        verify(claimedExecutor).earnClaimed(command);
        verify(claimService).accept(command.requestId().toString());
    }

    @Test
    void ACCEPTED_durable_요청은_Redis_키가_없어도_완료로_조회한다() {
        EarnCommand command = onceCommand();
        when(claimService.find(any(), any())).thenReturn(TicketOnceEarnRequestClaim.ACCEPTED);

        EarnLookupResult result = service.findExisting(command);

        assertThat(result.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
        verify(claimedExecutor, never()).findExistingClaimed(any());
    }

    @Test
    void 자정을_넘긴_같은_requestId의_ACCEPTED_요청도_완료로_조회한다() {
        EarnCommand firstDay = onceCommand();
        EarnCommand nextDay = new EarnCommand(firstDay.requestId(), 1L, 2L, "SHARE", 3L,
                "2026-09-17", "share:2", 1L, EarnRewardPolicy.ONCE);
        when(claimService.find(any(), any())).thenReturn(TicketOnceEarnRequestClaim.ACCEPTED);

        EarnLookupResult result = service.findExisting(nextDay);

        assertThat(result.status()).isEqualTo(EarnLookupStatus.ALREADY_PROCESSED);
        verify(claimedExecutor, never()).findExistingClaimed(any());
    }

    private EarnCommand onceCommand() {
        return new EarnCommand(UUID.randomUUID(), 1L, 2L, "SHARE", 3L, "2026-09-16", "share:2", 1L,
                EarnRewardPolicy.ONCE);
    }
}

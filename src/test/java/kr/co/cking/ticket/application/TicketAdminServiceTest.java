package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.application.dto.AdminTicketLedgerPage;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerItemResponse;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerPage;
import kr.co.cking.ticket.application.dto.TicketLedgerItemResponse;
import kr.co.cking.ticket.application.dto.TicketLedgerPage;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

class TicketAdminServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final Long CREATOR_ID = 3L;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final UserTicketBalanceRepository userTicketBalanceRepository = mock(UserTicketBalanceRepository.class);
    private final TicketCompensationService ticketCompensationService = mock(TicketCompensationService.class);
    private final TicketBalanceQueryService ticketBalanceQueryService = mock(TicketBalanceQueryService.class);
    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository =
            mock(UserCommonTicketBalanceRepository.class);
    private final CommonTicketCompensationService commonTicketCompensationService =
            mock(CommonTicketCompensationService.class);
    private final CommonTicketBalanceQueryService commonTicketBalanceQueryService =
            mock(CommonTicketBalanceQueryService.class);
    private final TicketQueryService ticketQueryService = mock(TicketQueryService.class);
    private final CommonTicketQueryService commonTicketQueryService = mock(CommonTicketQueryService.class);
    private final TicketAdminService service = new TicketAdminService(
            memberQueryService, userTicketBalanceRepository, ticketCompensationService, ticketBalanceQueryService,
            userCommonTicketBalanceRepository, commonTicketCompensationService, commonTicketBalanceQueryService,
            ticketQueryService, commonTicketQueryService);

    @Test
    void ADMIN은_Creator_전용_이력을_기존_조회_서비스로_반환한다() {
        TicketLedgerItemResponse item = new TicketLedgerItemResponse(
                10L, 3L, "EARN", 5L, null, "출석", "request-1", Instant.parse("2026-10-13T00:00:00Z"));
        when(ticketQueryService.getLedger(CREATOR_ID, MEMBER_ID, 20, null))
                .thenReturn(new TicketLedgerPage(MEMBER_ID, CREATOR_ID, java.util.List.of(item), null, false));

        AdminTicketLedgerPage result = service.getHistory(
                ADMIN_ID, MEMBER_ID, CouponType.CREATOR, CREATOR_ID, 20, null);

        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.couponType()).isEqualTo(CouponType.CREATOR);
        assertThat(result.creatorId()).isEqualTo(CREATOR_ID);
        assertThat(result.items()).containsExactly(item);
        InOrder inOrder = inOrder(memberQueryService, ticketQueryService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(ticketQueryService).getLedger(CREATOR_ID, MEMBER_ID, 20, null);
    }

    @Test
    void ADMIN은_공용_이력을_기존_조회_서비스로_반환한다() {
        CommonTicketLedgerItemResponse item = new CommonTicketLedgerItemResponse(
                11L, -2L, "SPEND", null, 7L, null, "request-2", Instant.parse("2026-10-13T00:00:00Z"));
        when(commonTicketQueryService.getLedger(MEMBER_ID, 20, "cursor"))
                .thenReturn(new CommonTicketLedgerPage(MEMBER_ID, java.util.List.of(item), "next", true));

        AdminTicketLedgerPage result = service.getHistory(
                ADMIN_ID, MEMBER_ID, CouponType.COMMON, null, 20, "cursor");

        assertThat(result.couponType()).isEqualTo(CouponType.COMMON);
        assertThat(result.creatorId()).isNull();
        assertThat(result.items()).extracting(TicketLedgerItemResponse::ledgerId).containsExactly(11L);
        assertThat(result.nextCursor()).isEqualTo("next");
        assertThat(result.hasNext()).isTrue();
        verify(commonTicketQueryService).getLedger(MEMBER_ID, 20, "cursor");
    }

    @Test
    void Creator_전용_조회에_creatorId가_없으면_400이고_조회하지_않는다() {
        assertThatThrownBy(() -> service.getHistory(ADMIN_ID, MEMBER_ID, CouponType.CREATOR, null, 20, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);

        verify(ticketQueryService, never()).getLedger(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ADMIN이_아니면_이력을_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.getHistory(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null, 20, null))
                .isInstanceOf(BusinessException.class);

        verify(commonTicketQueryService, never()).getLedger(MEMBER_ID, 20, null);
    }

    @Test
    void ADMIN_검증후_대상이_있으면_보정하고_현재_잔액을_반환한다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(10L).updatedAt(Instant.now()).build()));
        TicketBalanceResponse response = new TicketBalanceResponse(MEMBER_ID, CREATOR_ID, 10L, Instant.now());
        when(ticketBalanceQueryService.getBalanceDetail(CREATOR_ID, MEMBER_ID)).thenReturn(response);

        TicketBalanceResponse result = service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "정합성 배치 지속 불일치");

        assertThat(result).isSameAs(response);
        InOrder inOrder = inOrder(memberQueryService, ticketCompensationService, ticketBalanceQueryService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(ticketCompensationService)
                .resyncRedisToDb(MEMBER_ID, CREATOR_ID, "정합성 배치 지속 불일치");
        inOrder.verify(ticketBalanceQueryService).getBalanceDetail(CREATOR_ID, MEMBER_ID);
    }

    @Test
    void ADMIN이_아니면_보정하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class);

        verify(ticketCompensationService, never()).resyncRedisToDb(eq(MEMBER_ID), eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 대상_Balance가_없으면_RESOURCE_NOT_FOUND다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(ticketCompensationService, never()).resyncRedisToDb(eq(MEMBER_ID), eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 보정_서비스가_거부하면_그대로_전파한다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(10L).updatedAt(Instant.now()).build()));
        doThrow(new BusinessException(kr.co.cking.ticket.domain.TicketErrorCode.INVALID_STATE))
                .when(ticketCompensationService).resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유");

        assertThatThrownBy(() -> service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class);
        verify(ticketBalanceQueryService, never()).getBalanceDetail(CREATOR_ID, MEMBER_ID);
    }

    @Test
    void 공용_잔액은_ADMIN_검증후_보정하고_현재_잔액을_반환한다() {
        when(userCommonTicketBalanceRepository.existsById(MEMBER_ID)).thenReturn(true);
        CommonTicketBalanceResponse response = new CommonTicketBalanceResponse(MEMBER_ID, 10L, Instant.now());
        when(commonTicketBalanceQueryService.getBalanceDetail(MEMBER_ID)).thenReturn(response);

        assertThat(service.resyncCommon(ADMIN_ID, MEMBER_ID, "사유")).isSameAs(response);
        InOrder inOrder = inOrder(memberQueryService, commonTicketCompensationService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(commonTicketCompensationService).resyncRedisToDb(MEMBER_ID, "사유");
    }

    @Test
    void 공용_잔액_행이_없으면_404이고_보정하지_않는다() {
        when(userCommonTicketBalanceRepository.existsById(MEMBER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.resyncCommon(ADMIN_ID, MEMBER_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(commonTicketCompensationService, never()).resyncRedisToDb(eq(MEMBER_ID), eq("사유"));
    }
}

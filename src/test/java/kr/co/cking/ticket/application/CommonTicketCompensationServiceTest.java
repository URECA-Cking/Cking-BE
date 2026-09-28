package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;

class CommonTicketCompensationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String TOKEN = "token-1";

    private final UserCommonTicketBalanceRepository balanceRepository = mock(UserCommonTicketBalanceRepository.class);
    private final CommonTicketLedgerRepository ledgerRepository = mock(CommonTicketLedgerRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TicketMaintenanceLock lock = mock(TicketMaintenanceLock.class);
    private final UnappliedBalanceMessageChecker checker = mock(UnappliedBalanceMessageChecker.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final ValueOperations<String, String> valueOperations = mockValueOperations();

    private CommonTicketCompensationService service;

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockValueOperations() {
        return mock(ValueOperations.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new CommonTicketCompensationService(
                balanceRepository, ledgerRepository, redisTemplate, lock, checker, transactionTemplate);
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CommonTicketRedisKeys.balance(MEMBER_ID))).thenReturn("3");
        when(balanceRepository.findByMemberIdForUpdate(MEMBER_ID))
                .thenReturn(Optional.of(UserCommonTicketBalance.builder().memberId(MEMBER_ID).balance(10L).build()));
    }

    @Test
    void 이미_보정_lock이_잡혀_있으면_검사도_보정도_하지_않고_거부한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.CONCURRENT_COMMAND);
        verifyNoInteractions(checker, transactionTemplate);
        verify(lock, never()).releaseCommon(anyLong(), any());
    }

    @Test
    void 미반영_메시지가_있으면_덮어쓰지_않고_거부하며_lock을_해제한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(checker.existsCommon(MEMBER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.INVALID_STATE);
        verifyNoInteractions(transactionTemplate);
        verify(lock).releaseCommon(MEMBER_ID, TOKEN);
    }

    @Test
    void 덮어쓰는_시점에_lock을_잃었으면_거부하고_lock을_해제한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(lock.setCommonBalanceIfHeld(MEMBER_ID, TOKEN, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.CONCURRENT_COMMAND);
        verify(lock).releaseCommon(MEMBER_ID, TOKEN);
    }

    @Test
    void 통과하면_COMPENSATE_Ledger를_남기고_DB_기준으로_덮어쓴다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(lock.setCommonBalanceIfHeld(MEMBER_ID, TOKEN, 10L)).thenReturn(true);

        service.resyncRedisToDb(MEMBER_ID, "사유");

        InOrder inOrder = inOrder(lock, checker, ledgerRepository);
        inOrder.verify(lock).acquireCommon(MEMBER_ID);
        inOrder.verify(checker).existsCommon(MEMBER_ID);
        inOrder.verify(ledgerRepository).save(any());
        inOrder.verify(lock).setCommonBalanceIfHeld(MEMBER_ID, TOKEN, 10L);
        inOrder.verify(lock).releaseCommon(MEMBER_ID, TOKEN);

        ArgumentCaptor<CommonTicketLedger> saved = ArgumentCaptor.forClass(CommonTicketLedger.class);
        verify(ledgerRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(TicketLedgerType.COMPENSATE);
        assertThat(saved.getValue().getDeltaAmount()).isEqualTo(7L);
        assertThat(saved.getValue().getBalanceBefore()).isEqualTo(3L);
        assertThat(saved.getValue().getBalanceAfter()).isEqualTo(10L);
    }
}

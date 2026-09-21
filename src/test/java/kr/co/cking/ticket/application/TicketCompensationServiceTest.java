package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

class TicketCompensationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long CREATOR_ID = 10L;
    private static final String TOKEN = "token-1";

    private final UserTicketBalanceRepository balanceRepository = mock(UserTicketBalanceRepository.class);
    private final TicketLedgerRepository ledgerRepository = mock(TicketLedgerRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TicketMaintenanceLock lock = mock(TicketMaintenanceLock.class);
    private final UnappliedBalanceMessageChecker checker = mock(UnappliedBalanceMessageChecker.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private TicketCompensationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new TicketCompensationService(
                balanceRepository, ledgerRepository, redisTemplate, lock, checker, transactionTemplate);
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn("3");
        when(balanceRepository.findByMemberIdAndCreatorIdForUpdate(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(10L).build()));
    }

    @Test
    void 이미_보정_lock이_잡혀_있으면_검사도_보정도_하지_않고_거부한다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.CONCURRENT_COMMAND);
        verifyNoInteractions(checker, transactionTemplate);
        verify(lock, never()).release(anyLong(), anyLong(), any());
    }

    @Test
    void 미반영_메시지가_있으면_Redis를_덮어쓰지_않고_거부하며_lock을_해제한다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.INVALID_STATE);
        verifyNoInteractions(transactionTemplate);
        verify(lock, never()).setBalanceIfHeld(anyLong(), anyLong(), any(), anyLong());
        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }

    @Test
    void 덮어쓰는_시점에_lock을_잃었으면_거부하고_lock을_해제한다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(false);
        when(lock.setBalanceIfHeld(CREATOR_ID, MEMBER_ID, TOKEN, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.CONCURRENT_COMMAND);
        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }

    @Test
    void 통과하면_lock_획득_검사_덮어쓰기_해제_순서로_보정한다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(false);
        when(lock.setBalanceIfHeld(eq(CREATOR_ID), eq(MEMBER_ID), eq(TOKEN), eq(10L))).thenReturn(true);

        service.resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유");

        InOrder inOrder = inOrder(lock, checker, ledgerRepository);
        inOrder.verify(lock).acquire(CREATOR_ID, MEMBER_ID);
        inOrder.verify(checker).exists(MEMBER_ID, CREATOR_ID);
        inOrder.verify(ledgerRepository).save(any());
        inOrder.verify(lock).setBalanceIfHeld(CREATOR_ID, MEMBER_ID, TOKEN, 10L);
        inOrder.verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }
}

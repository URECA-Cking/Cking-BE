package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionStore.FinishOutcome;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnResult;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuizRewardEarnWorkerTest {

    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final String BALANCE_KEY = TicketRedisKeys.balance(10L, 7L);

    private final TicketQuizRewardRequestRepository quizRepository = mock(TicketQuizRewardRequestRepository.class);
    private final QuizRewardExecutionClaimService claimService = mock(QuizRewardExecutionClaimService.class);
    private final QuizRewardExecutionStore executionStore = mock(QuizRewardExecutionStore.class);
    private final QuizTicketEarnRedisExecutor redisExecutor = mock(QuizTicketEarnRedisExecutor.class);
    private final TicketBalanceKeyLoader balanceKeyLoader = mock(TicketBalanceKeyLoader.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
    private final QuizRewardEarnWorker worker = worker(true);

    @BeforeEach
    void setUp() {
        TicketQuizRewardRequest stored = new TicketQuizRewardRequest(REQUEST_ID.toString(), 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), Instant.now());
        ReflectionTestUtils.setField(stored, "quizRewardRequestId", 1L);
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.of(stored));
        given(claimService.claim(command)).willReturn(QuizRewardExecutionClaim.claimed(1L, TOKEN, 1));
        given(redisTemplate.hasKey(BALANCE_KEY)).willReturn(true);
        given(redisExecutor.execute(any())).willReturn(accepted());
        given(executionStore.finish(eq(REQUEST_ID), eq(TOKEN), any())).willReturn(FinishOutcome.RECORDED);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 실행권을_얻은_뒤_저장된_입력과_실행권으로_Lua를_한_번_부르고_결과를_기록한다() {
        QuizRewardWorkResult result = worker.run(REQUEST_ID);

        InOrder order = inOrder(quizRepository, claimService, redisTemplate, redisExecutor, executionStore);
        order.verify(quizRepository).findByRewardRequestId(REQUEST_ID.toString());
        order.verify(claimService).claim(command);
        order.verify(redisTemplate).hasKey(BALANCE_KEY);
        order.verify(redisExecutor).execute(any());
        order.verify(executionStore).finish(REQUEST_ID, TOKEN, accepted());
        ArgumentCaptor<QuizEarnEvent> event = ArgumentCaptor.forClass(QuizEarnEvent.class);
        verify(redisExecutor).execute(event.capture());
        assertThat(event.getValue()).isEqualTo(new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), TOKEN));
        verifyNoInteractions(balanceKeyLoader);
        assertThat(result).isEqualTo(new QuizRewardWorkResult(QuizRewardWorkResult.Status.RECORDED,
                QuizRewardExecutionClaim.Outcome.CLAIMED, QuizRedisEarnOutcome.ACCEPTED, FinishOutcome.RECORDED));
    }

    @Test
    void 실행권을_얻지_못하면_Redis를_부르지_않는다() {
        for (QuizRewardExecutionClaim.Outcome outcome : QuizRewardExecutionClaim.Outcome.values()) {
            if (outcome == QuizRewardExecutionClaim.Outcome.CLAIMED) {
                continue;
            }
            given(claimService.claim(command)).willReturn(QuizRewardExecutionClaim.rejected(outcome));

            QuizRewardWorkResult result = worker.run(REQUEST_ID);

            assertThat(result).isEqualTo(new QuizRewardWorkResult(QuizRewardWorkResult.Status.NOT_CLAIMED, outcome,
                    null, null));
        }
        verifyNoInteractions(redisTemplate, balanceKeyLoader, redisExecutor, executionStore);
    }

    @Test
    void 같은_요청을_두_Worker가_실행해도_실행권을_얻은_쪽만_Redis를_부른다() {
        given(claimService.claim(command)).willReturn(QuizRewardExecutionClaim.claimed(1L, TOKEN, 1),
                QuizRewardExecutionClaim.rejected(QuizRewardExecutionClaim.Outcome.ALREADY_EXECUTING));

        QuizRewardWorkResult first = worker.run(REQUEST_ID);
        QuizRewardWorkResult second = worker.run(REQUEST_ID);

        assertThat(first.status()).isEqualTo(QuizRewardWorkResult.Status.RECORDED);
        assertThat(second.status()).isEqualTo(QuizRewardWorkResult.Status.NOT_CLAIMED);
        verify(redisExecutor, times(1)).execute(any());
        verify(executionStore, times(1)).finish(any(), anyString(), any());
    }

    @Test
    void UNKNOWN이면_기록만_하고_Lua를_다시_부르지_않는다() {
        given(redisExecutor.execute(any())).willReturn(QuizRedisEarnResult.unknown("TIMEOUT"));

        QuizRewardWorkResult result = worker.run(REQUEST_ID);

        assertThat(result.redisOutcome()).isEqualTo(QuizRedisEarnOutcome.UNKNOWN);
        verify(redisExecutor, times(1)).execute(any());
        verify(executionStore).finish(REQUEST_ID, TOKEN, QuizRedisEarnResult.unknown("TIMEOUT"));
    }

    @Test
    void Redis_적립_뒤_결과_기록이_실패해도_다시_실행하지_않고_기록_실패로_끝낸다() {
        given(executionStore.finish(eq(REQUEST_ID), eq(TOKEN), any()))
                .willThrow(new CannotCreateTransactionException("db down"));

        QuizRewardWorkResult result = worker.run(REQUEST_ID);

        assertThat(result).isEqualTo(new QuizRewardWorkResult(QuizRewardWorkResult.Status.RECORD_FAILED,
                QuizRewardExecutionClaim.Outcome.CLAIMED, QuizRedisEarnOutcome.ACCEPTED, null));
        verify(redisExecutor, times(1)).execute(any());
        verify(executionStore, times(1)).finish(any(), anyString(), any());
        verify(claimService, times(1)).claim(any());
    }

    @Test
    void Balance_키가_없으면_DB_기준_적재가_된_뒤에만_Lua를_부른다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willReturn(false);
        given(balanceKeyLoader.load(CouponType.CREATOR, 10L, 7L)).willReturn(true);

        worker.run(REQUEST_ID);

        InOrder order = inOrder(quizRepository, balanceKeyLoader, redisExecutor);
        order.verify(quizRepository).countUnsettled(7L, 10L, 1L, TOKEN);
        order.verify(balanceKeyLoader).load(CouponType.CREATOR, 10L, 7L);
        order.verify(redisExecutor).execute(any());
    }

    @Test
    void Balance_키를_적재하지_못하면_Lua를_부르지_않고_무반영으로_기록한다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willReturn(false);
        given(balanceKeyLoader.load(CouponType.CREATOR, 10L, 7L)).willReturn(false);

        QuizRewardWorkResult result = worker.run(REQUEST_ID);

        verify(redisExecutor, never()).execute(any());
        QuizRedisEarnResult expected = QuizRedisEarnResult.of(QuizRedisEarnOutcome.BALANCE_NOT_LOADED,
                QuizRewardEarnWorker.BALANCE_LOAD_FAILED);
        verify(executionStore).finish(REQUEST_ID, TOKEN, expected);
        assertThat(result.redisOutcome()).isEqualTo(QuizRedisEarnOutcome.BALANCE_NOT_LOADED);
        assertThat(QuizRedisEarnOutcome.BALANCE_NOT_LOADED.confirmedNotExecuted()).isTrue();
    }

    @Test
    void Balance_키가_있으면_미확정_지급_확인과_적재_없이_Lua를_부른다() {
        worker.run(REQUEST_ID);

        verify(quizRepository, never()).countUnsettled(any(), any(), any(), any());
        verifyNoInteractions(balanceKeyLoader);
        verify(redisExecutor).execute(any());
    }

    @Test
    void Balance_키가_없을_때_미확정_Quiz_지급이_있으면_적재도_Lua도_하지_않고_무반영으로_기록한다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willReturn(false);
        given(quizRepository.countUnsettled(7L, 10L, 1L, TOKEN)).willReturn(1L);

        QuizRewardWorkResult result = worker.run(REQUEST_ID);

        // 현재 요청은 요청 ID가 아니라 이번 실행권만 빼고 센다. 과거 실행은 그대로 검사 대상이다.
        verify(quizRepository).countUnsettled(7L, 10L, 1L, TOKEN);
        verifyNoInteractions(balanceKeyLoader);
        verify(redisExecutor, never()).execute(any());
        verify(executionStore).finish(REQUEST_ID, TOKEN, QuizRedisEarnResult.of(
                QuizRedisEarnOutcome.BALANCE_NOT_LOADED, QuizRewardEarnWorker.UNSETTLED_QUIZ_REWARD));
        assertThat(result.redisOutcome()).isEqualTo(QuizRedisEarnOutcome.BALANCE_NOT_LOADED);
    }

    @Test
    void 미확정_지급을_확인하지_못하면_적재도_Lua도_하지_않는다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willReturn(false);
        given(quizRepository.countUnsettled(7L, 10L, 1L, TOKEN)).willThrow(new CannotCreateTransactionException("db"));

        worker.run(REQUEST_ID);

        verifyNoInteractions(balanceKeyLoader);
        verify(redisExecutor, never()).execute(any());
        verify(executionStore).finish(REQUEST_ID, TOKEN, QuizRedisEarnResult.of(
                QuizRedisEarnOutcome.BALANCE_NOT_LOADED, QuizRewardEarnWorker.BALANCE_CHECK_FAILED));
    }

    @Test
    void 적재_중_오류가_나도_Lua를_부르지_않는다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willReturn(false);
        given(balanceKeyLoader.load(CouponType.CREATOR, 10L, 7L)).willThrow(new IllegalStateException("db"));

        worker.run(REQUEST_ID);

        verify(redisExecutor, never()).execute(any());
        verify(executionStore).finish(REQUEST_ID, TOKEN, QuizRedisEarnResult.of(
                QuizRedisEarnOutcome.BALANCE_NOT_LOADED, QuizRewardEarnWorker.BALANCE_LOAD_FAILED));
    }

    @Test
    void Balance_키를_확인하지_못하면_적재도_Lua도_부르지_않는다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willThrow(new RedisConnectionFailureException("down"));

        worker.run(REQUEST_ID);

        willReturn(null).given(redisTemplate).hasKey(BALANCE_KEY);
        worker.run(REQUEST_ID);

        verifyNoInteractions(balanceKeyLoader);
        verify(redisExecutor, never()).execute(any());
        verify(executionStore, times(2)).finish(REQUEST_ID, TOKEN, QuizRedisEarnResult.of(
                QuizRedisEarnOutcome.BALANCE_NOT_LOADED, QuizRewardEarnWorker.BALANCE_CHECK_FAILED));
    }

    @Test
    void 키_확인_타임아웃도_Lua를_부르지_않는다() {
        given(redisTemplate.hasKey(BALANCE_KEY)).willThrow(new QueryTimeoutException("timeout"));

        worker.run(REQUEST_ID);

        verify(redisExecutor, never()).execute(any());
    }

    @Test
    void 요청이_없으면_실행권을_발급하지_않는다() {
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.empty());

        assertThat(worker.run(REQUEST_ID).status()).isEqualTo(QuizRewardWorkResult.Status.REQUEST_NOT_FOUND);
        verifyNoInteractions(claimService, redisTemplate, balanceKeyLoader, redisExecutor, executionStore);
    }

    @Test
    void 꺼져_있으면_아무것도_하지_않는다() {
        assertThat(worker(false).run(REQUEST_ID).status()).isEqualTo(QuizRewardWorkResult.Status.DISABLED);
        verifyNoInteractions(quizRepository, claimService, redisTemplate, balanceKeyLoader, redisExecutor,
                executionStore);
    }

    @Test
    void Transaction_안에서는_실행하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> worker.run(REQUEST_ID)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(quizRepository, claimService, redisTemplate, balanceKeyLoader, redisExecutor,
                executionStore);
    }

    @Test
    void 실행권_발급이_실패하면_예외를_올리고_Redis를_부르지_않는다() {
        given(claimService.claim(command)).willThrow(new CannotCreateTransactionException("db down"));

        assertThatThrownBy(() -> worker.run(REQUEST_ID)).isInstanceOf(CannotCreateTransactionException.class);
        verifyNoInteractions(redisTemplate, balanceKeyLoader, redisExecutor, executionStore);
    }

    @Test
    void 결과_모델은_단계별_값이_맞지_않으면_만들_수_없다() {
        assertThatThrownBy(() -> new QuizRewardWorkResult(QuizRewardWorkResult.Status.NOT_CLAIMED,
                QuizRewardExecutionClaim.Outcome.CLAIMED, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRewardWorkResult(QuizRewardWorkResult.Status.RECORDED,
                QuizRewardExecutionClaim.Outcome.CLAIMED, QuizRedisEarnOutcome.ACCEPTED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRewardWorkResult(QuizRewardWorkResult.Status.RECORD_FAILED,
                QuizRewardExecutionClaim.Outcome.CLAIMED, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRewardWorkResult(QuizRewardWorkResult.Status.DISABLED,
                QuizRewardExecutionClaim.Outcome.CLAIMED, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private QuizRewardEarnWorker worker(boolean enabled) {
        return new QuizRewardEarnWorker(quizRepository, claimService, executionStore, redisExecutor, balanceKeyLoader,
                redisTemplate, enabled);
    }

    private static QuizRedisEarnResult accepted() {
        return new QuizRedisEarnResult(QuizRedisEarnOutcome.ACCEPTED, "1-0", 5L, null, null);
    }
}

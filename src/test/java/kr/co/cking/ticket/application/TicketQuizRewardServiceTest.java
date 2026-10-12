package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TicketQuizRewardServiceTest {

    private final QuizRewardRequestStore store = mock(QuizRewardRequestStore.class);
    private final TicketQuizRewardService service = new TicketQuizRewardService(store);
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, UUID.randomUUID());

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 지급_상태는_rewardRequestId로_조회하고_접수되지_않았으면_빈_값이다() {
        given(store.findStatus(command.rewardRequestId().toString()))
                .willReturn(Optional.of(QuizRewardRequestStatus.APPLIED));
        UUID unknown = UUID.randomUUID();
        given(store.findStatus(unknown.toString())).willReturn(Optional.empty());

        assertThat(service.findStatus(command.rewardRequestId())).contains(QuizRewardRequestStatus.APPLIED);
        assertThat(service.findStatus(unknown)).isEmpty();
    }

    @Test
    void 처음_보는_요청은_새로_접수한다() {
        given(store.findExisting(command)).willReturn(Optional.empty());
        given(store.insert(command)).willReturn(QuizRewardAdmission.received(1L, QuizRewardRequestStatus.RECEIVED));

        assertThat(service.admit(command).outcome()).isEqualTo(QuizRewardAdmission.Outcome.RECEIVED);
        InOrder order = inOrder(store);
        order.verify(store).findExisting(command);
        order.verify(store).insert(command);
    }

    @Test
    void 이미_접수된_요청이나_충돌은_새로_만들지_않고_그대로_돌려준다() {
        for (QuizRewardAdmission existing : new QuizRewardAdmission[] {
                QuizRewardAdmission.replayed(1L, QuizRewardRequestStatus.RECEIVED),
                QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT),
                QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT)}) {
            given(store.findExisting(command)).willReturn(Optional.of(existing));

            assertThat(service.admit(command)).isEqualTo(existing);
        }
        verify(store, never()).insert(any());
    }

    @Test
    void 동시_접수와_겹쳐_실패하면_롤백된_뒤_새_Transaction에서_다시_판정한다() {
        QuizRewardAdmission[] judged = {
                QuizRewardAdmission.replayed(1L, QuizRewardRequestStatus.RECEIVED),
                QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT),
                QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT)};
        RuntimeException[] failures = {new DataIntegrityViolationException("duplicate"),
                new CannotAcquireLockException("deadlock")};
        for (RuntimeException failure : failures) {
            for (QuizRewardAdmission expected : judged) {
                willThrow(failure).given(store).insert(command);
                given(store.findExisting(command)).willReturn(Optional.empty(), Optional.of(expected));

                assertThat(service.admit(command)).isEqualTo(expected);
            }
        }
    }

    @Test
    void 고유_제약_실패_뒤에도_기존_요청이_없으면_재시도_대상이_아닌_오류로_알린다() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("fk");
        given(store.findExisting(command)).willReturn(Optional.empty());
        willThrow(failure).given(store).insert(command);

        assertThatThrownBy(() -> service.admit(command))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasCause(failure);
    }

    @Test
    void 잠금_경쟁_실패_뒤에도_기존_요청이_없으면_같은_요청으로_재시도할_수_있는_예외로_알린다() {
        CannotAcquireLockException failure = new CannotAcquireLockException("deadlock");
        given(store.findExisting(command)).willReturn(Optional.empty());
        willThrow(failure).given(store).insert(command);

        assertThatThrownBy(() -> service.admit(command))
                .isInstanceOf(QuizRewardAdmissionRetryableException.class)
                .hasCause(failure);
    }

    @Test
    void 회원_Creator_없음은_재판정하지_않고_그대로_알린다() {
        given(store.findExisting(command)).willReturn(Optional.empty());
        willThrow(new IllegalArgumentException("no member")).given(store).insert(command);

        assertThatThrownBy(() -> service.admit(command)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이미_열린_Transaction_안에서는_호출할_수_없다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> service.admit(command)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }
}

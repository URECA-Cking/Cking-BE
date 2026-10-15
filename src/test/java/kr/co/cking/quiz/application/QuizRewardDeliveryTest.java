package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import kr.co.cking.quiz.application.dto.QuizRewardPaymentStatus;
import kr.co.cking.quiz.domain.QuizAttempt;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.repository.QuizAttemptRepository;
import kr.co.cking.ticket.application.QuizRewardAdmission;
import kr.co.cking.ticket.application.QuizRewardEarnWorker;
import kr.co.cking.ticket.application.TicketQuizRewardService;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuizRewardDeliveryTest {

    private static final UUID REWARD_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final TicketQuizRewardService rewardService = mock(TicketQuizRewardService.class);
    private final QuizRewardEarnWorker worker = mock(QuizRewardEarnWorker.class);
    private final QuizAttemptRepository attempts = mock(QuizAttemptRepository.class);
    private final QuizAttemptSnapshotReader reader = mock(QuizAttemptSnapshotReader.class);
    private final QuizRewardDelivery delivery = new QuizRewardDelivery(rewardService, worker, attempts, reader);

    private static QuizAttemptResult result(long attemptId, int correctCount, UUID rewardId) {
        return new QuizAttemptResult(attemptId, UUID.randomUUID(), 10L, 20L, 30L, "제목", "video", 3, correctCount,
                Instant.parse("2026-10-12T00:00:00Z"), "a".repeat(64), "b".repeat(64), List.of(),
                new QuizAttemptResult.Reward(correctCount, rewardId, correctCount > 0 ? "NOT_INTEGRATED" : "NOT_ELIGIBLE"));
    }

    private void admitted(QuizRewardAdmission.Outcome outcome, QuizRewardRequestStatus status) {
        doReturn(outcome == QuizRewardAdmission.Outcome.RECEIVED || outcome == QuizRewardAdmission.Outcome.REPLAYED
                ? new QuizRewardAdmission(outcome, 1L, status) : QuizRewardAdmission.conflict(outcome))
                .when(rewardService).admit(any());
    }

    @Test
    void 양수_점수는_저장된_값과_rewardRequestId로_접수한_뒤_실행한다() {
        admitted(QuizRewardAdmission.Outcome.RECEIVED, QuizRewardRequestStatus.RECEIVED);

        delivery.deliver(result(5L, 2, REWARD_ID), 7L);

        verify(rewardService).admit(new QuizRewardCommand(5L, 7L, 10L, 2, REWARD_ID));
        verify(worker).run(REWARD_ID);
    }

    @Test
    void 정답이_0개면_접수도_실행도_하지_않는다() {
        delivery.deliver(result(5L, 0, null), 7L);

        verifyNoInteractions(rewardService, worker);
    }

    @Test
    void 재전달은_같은_rewardRequestId로_접수하고_실행_가능한_상태에서만_실행한다() {
        for (QuizRewardRequestStatus status : List.of(QuizRewardRequestStatus.EXECUTING,
                QuizRewardRequestStatus.ACCEPTED, QuizRewardRequestStatus.UNKNOWN, QuizRewardRequestStatus.APPLIED)) {
            admitted(QuizRewardAdmission.Outcome.REPLAYED, status);
            delivery.deliver(result(5L, 2, REWARD_ID), 7L);
        }
        verify(worker, never()).run(any());

        admitted(QuizRewardAdmission.Outcome.REPLAYED, QuizRewardRequestStatus.RETRYABLE_FAILURE);
        delivery.deliver(result(5L, 2, REWARD_ID), 7L);
        admitted(QuizRewardAdmission.Outcome.REPLAYED, QuizRewardRequestStatus.RECEIVED);
        delivery.deliver(result(5L, 2, REWARD_ID), 7L);

        // 매번 저장된 같은 UUID를 쓰고, 실행 여부는 Ticket 엔진의 실행권 규칙이 정한다.
        verify(rewardService, org.mockito.Mockito.times(6)).admit(new QuizRewardCommand(5L, 7L, 10L, 2, REWARD_ID));
        verify(worker, org.mockito.Mockito.times(2)).run(REWARD_ID);
    }

    @Test
    void 접수_충돌이면_실행하지_않는다() {
        for (QuizRewardAdmission.Outcome outcome : List.of(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT,
                QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT)) {
            admitted(outcome, null);
            delivery.deliver(result(5L, 2, REWARD_ID), 7L);
        }
        verify(worker, never()).run(any());
    }

    @Test
    void 접수나_실행이_실패해도_예외를_올리지_않는다() {
        when(rewardService.admit(any())).thenThrow(new QueryTimeoutException("db"));
        delivery.deliver(result(5L, 2, REWARD_ID), 7L);
        verify(worker, never()).run(any());

        admitted(QuizRewardAdmission.Outcome.RECEIVED, QuizRewardRequestStatus.RECEIVED);
        when(worker.run(REWARD_ID)).thenThrow(new IllegalStateException("redis"));
        delivery.deliver(result(5L, 2, REWARD_ID), 7L);
    }

    @Test
    void 지급_상태는_APPLIED만_PAID이고_UNKNOWN은_DELAYED다() {
        QuizAttemptResult positive = result(5L, 2, REWARD_ID);
        assertPayment(positive, Optional.of(QuizRewardRequestStatus.APPLIED), QuizRewardPaymentStatus.PAID);
        assertPayment(positive, Optional.of(QuizRewardRequestStatus.UNKNOWN), QuizRewardPaymentStatus.DELAYED);
        for (QuizRewardRequestStatus status : List.of(QuizRewardRequestStatus.RECEIVED,
                QuizRewardRequestStatus.EXECUTING, QuizRewardRequestStatus.ACCEPTED,
                QuizRewardRequestStatus.RETRYABLE_FAILURE)) {
            assertPayment(positive, Optional.of(status), QuizRewardPaymentStatus.PENDING);
        }
        assertPayment(positive, Optional.empty(), QuizRewardPaymentStatus.PENDING);
        assertThat(delivery.paymentStatusOf(result(5L, 0, null))).isEqualTo(QuizRewardPaymentStatus.NOT_ELIGIBLE);
    }

    @Test
    void 지급_상태_조회가_실패하면_미접수가_아니라_UNAVAILABLE이다() {
        when(rewardService.findStatus(REWARD_ID)).thenThrow(new QueryTimeoutException("db"));

        assertThat(delivery.paymentStatusOf(result(5L, 2, REWARD_ID))).isEqualTo(QuizRewardPaymentStatus.UNAVAILABLE);
    }

    @Test
    void 재전달은_양수_점수_응시를_PK_순서로_제한해_읽고_검증된_결과만_전달한다() {
        QuizAttempt first = attempt(11L, 7L);
        QuizAttempt broken = attempt(12L, 8L);
        when(attempts.findByAttemptIdGreaterThanAndCorrectCountGreaterThanOrderByAttemptIdAsc(10L, 0,
                PageRequest.of(0, 2))).thenReturn(List.of(first, broken));
        UUID firstReward = UUID.randomUUID();
        when(reader.read(first)).thenReturn(result(11L, 1, firstReward));
        when(reader.read(broken)).thenThrow(new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
        admitted(QuizRewardAdmission.Outcome.RECEIVED, QuizRewardRequestStatus.RECEIVED);

        long next = delivery.redeliverAfter(10L, 2);

        assertThat(next).isEqualTo(12L);
        verify(rewardService).admit(new QuizRewardCommand(11L, 7L, 10L, 1, firstReward));
        verify(rewardService, org.mockito.Mockito.times(1)).admit(any());
    }

    @Test
    void 끝까지_읽으면_처음으로_돌아갈_기준을_돌려준다() {
        when(attempts.findByAttemptIdGreaterThanAndCorrectCountGreaterThanOrderByAttemptIdAsc(eq(12L), eq(0), any()))
                .thenReturn(List.of());

        assertThat(delivery.redeliverAfter(12L, 2)).isZero();
        verify(attempts).findByAttemptIdGreaterThanAndCorrectCountGreaterThanOrderByAttemptIdAsc(anyLong(), eq(0),
                eq(PageRequest.of(0, 2)));
    }

    private void assertPayment(QuizAttemptResult result, Optional<QuizRewardRequestStatus> status,
                               QuizRewardPaymentStatus expected) {
        when(rewardService.findStatus(result.reward().requestId())).thenReturn(status);
        assertThat(delivery.paymentStatusOf(result)).as(status.toString()).isEqualTo(expected);
    }

    private static QuizAttempt attempt(long attemptId, long memberId) {
        QuizAttempt attempt = mock(QuizAttempt.class);
        when(attempt.getAttemptId()).thenReturn(attemptId);
        when(attempt.getMemberId()).thenReturn(memberId);
        return attempt;
    }
}

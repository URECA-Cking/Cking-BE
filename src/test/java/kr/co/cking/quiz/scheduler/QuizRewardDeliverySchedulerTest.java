package kr.co.cking.quiz.scheduler;

import kr.co.cking.quiz.application.QuizRewardDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizRewardDeliverySchedulerTest {

    private final QuizRewardDelivery delivery = mock(QuizRewardDelivery.class);

    @Test
    void 커서를_이어가며_순회하고_끝나면_처음으로_돌아간다() {
        QuizRewardDeliveryScheduler scheduler = new QuizRewardDeliveryScheduler(delivery, 100);
        when(delivery.redeliverAfter(0L, 100)).thenReturn(150L);
        when(delivery.redeliverAfter(150L, 100)).thenReturn(0L);

        scheduler.redeliver();
        assertThat(scheduler.cursor()).isEqualTo(150L);
        scheduler.redeliver();
        assertThat(scheduler.cursor()).isZero();
        scheduler.redeliver();

        var order = inOrder(delivery);
        order.verify(delivery).redeliverAfter(0L, 100);
        order.verify(delivery).redeliverAfter(150L, 100);
        order.verify(delivery).redeliverAfter(0L, 100);
    }

    @Test
    void 재시작하면_처음부터_다시_훑는다() {
        when(delivery.redeliverAfter(0L, 100)).thenReturn(150L);
        new QuizRewardDeliveryScheduler(delivery, 100).redeliver();

        QuizRewardDeliveryScheduler restarted = new QuizRewardDeliveryScheduler(delivery, 100);

        assertThat(restarted.cursor()).isZero();
    }

    @Test
    void 후보_조회가_실패하면_커서를_유지하고_다음_주기에_같은_위치부터_읽는다() {
        QuizRewardDeliveryScheduler scheduler = new QuizRewardDeliveryScheduler(delivery, 100);
        when(delivery.redeliverAfter(0L, 100)).thenReturn(150L);
        scheduler.redeliver();
        when(delivery.redeliverAfter(150L, 100)).thenThrow(new QueryTimeoutException("db"));

        scheduler.redeliver();

        assertThat(scheduler.cursor()).isEqualTo(150L);
    }

    @Test
    void 배치_크기는_양수여야_한다() {
        assertThatThrownBy(() -> new QuizRewardDeliveryScheduler(delivery, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

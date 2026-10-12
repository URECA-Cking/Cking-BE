package kr.co.cking.ticket.application;

import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizRewardAdmissionTest {

    @Test
    void 접수와_재요청은_요청_정보를_담고_충돌은_담지_않는다() {
        assertThat(QuizRewardAdmission.received(1L, QuizRewardRequestStatus.RECEIVED).outcome())
                .isEqualTo(QuizRewardAdmission.Outcome.RECEIVED);
        assertThat(QuizRewardAdmission.replayed(1L, QuizRewardRequestStatus.APPLIED).status())
                .isEqualTo(QuizRewardRequestStatus.APPLIED);
        for (QuizRewardAdmission.Outcome outcome : new QuizRewardAdmission.Outcome[] {
                QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT, QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT}) {
            QuizRewardAdmission conflict = QuizRewardAdmission.conflict(outcome);
            assertThat(conflict.quizRewardRequestId()).isNull();
            assertThat(conflict.status()).isNull();
        }
    }

    @Test
    void 결과와_요청_정보가_맞지_않으면_만들지_않는다() {
        assertThatThrownBy(() -> QuizRewardAdmission.received(null, QuizRewardRequestStatus.RECEIVED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.RECEIVED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRewardAdmission(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT, 1L,
                QuizRewardRequestStatus.RECEIVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizRewardAdmission(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 충돌_결과에_요청_ID나_상태_하나만_있어도_거부한다() {
        for (QuizRewardAdmission.Outcome outcome : new QuizRewardAdmission.Outcome[] {
                QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT, QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT}) {
            assertThatThrownBy(() -> new QuizRewardAdmission(outcome, 1L, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QuizRewardAdmission(outcome, null, QuizRewardRequestStatus.RECEIVED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (QuizRewardAdmission.Outcome outcome : new QuizRewardAdmission.Outcome[] {
                QuizRewardAdmission.Outcome.RECEIVED, QuizRewardAdmission.Outcome.REPLAYED}) {
            assertThatThrownBy(() -> new QuizRewardAdmission(outcome, 1L, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QuizRewardAdmission(outcome, null, QuizRewardRequestStatus.RECEIVED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

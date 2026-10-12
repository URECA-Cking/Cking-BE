package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.domain.QuizErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeNotIntegratedQuizApprovalPolicyTest {

    private final JudgeNotIntegratedQuizApprovalPolicy policy = new JudgeNotIntegratedQuizApprovalPolicy();
    private final QuizApprovalPolicy.Target target =
            new QuizApprovalPolicy.Target(1L, 100L, "a".repeat(64), "b".repeat(64));

    @Test
    void 승인과_게시를_모두_거부한다() {
        assertThatThrownBy(() -> policy.requireApprovable(target))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));
        assertThatThrownBy(() -> policy.requireStillApproved(target, QuizFixtures.approval(100L, 7L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));
    }
}

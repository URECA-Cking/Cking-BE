package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import org.springframework.stereotype.Component;

/**
 * GPT Judge 평가를 아직 저장하지 않으므로 모든 승인·게시를 거부한다(이슈 #521).
 *
 * <p>검토되지 않은 Quiz가 운영에 게시되지 않게 하는 서버 측 차단이다. 게시 취소와 거절은 이 정책을 거치지 않는다.
 */
@Component
public class JudgeNotIntegratedQuizApprovalPolicy implements QuizApprovalPolicy {

    @Override
    public Basis requireApprovable(Target target) {
        throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
    }

    @Override
    public void requireStillApproved(Target target, QuizRevisionDecision approval) {
        throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
    }
}

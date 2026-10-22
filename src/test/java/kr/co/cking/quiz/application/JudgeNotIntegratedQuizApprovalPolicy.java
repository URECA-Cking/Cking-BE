package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevisionDecision;

/** 이전 차단 동작을 사용하는 명령 Service 단위 테스트 전용 정책. 운영 Bean으로 등록하지 않는다. */
class JudgeNotIntegratedQuizApprovalPolicy implements QuizApprovalPolicy {
    public Basis requireApprovable(Target target) {
        throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
    }
    public void requireStillApproved(Target target, QuizRevisionDecision approval) {
        throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
    }
}

package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;

/**
 * 테스트 전용 승인 정책. 모든 revision에 Judge 평가가 있다고 보고, 평가 해시를 revision 해시에서 만들어
 * 게시 시 같은 revision의 근거인지 확인한다. 운영 소스에는 두지 않는다.
 */
public class FakeQuizApprovalPolicy implements QuizApprovalPolicy {

    public static final long JUDGE_EVALUATION_ID = 77L;

    @Override
    public Basis requireApprovable(Target target) {
        return new Basis(JUDGE_EVALUATION_ID, evaluationSha256(target));
    }

    @Override
    public void requireStillApproved(Target target, QuizRevisionDecision approval) {
        if (!evaluationSha256(target).equals(approval.getJudgeEvaluationSha256())) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        }
    }

    public static String evaluationSha256(Target target) {
        return QuizRevisionFingerprint.contentTextSha256("judge:" + target.revisionSha256());
    }
}

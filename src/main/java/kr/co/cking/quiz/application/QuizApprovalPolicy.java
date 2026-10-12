package kr.co.cking.quiz.application;

import kr.co.cking.quiz.domain.QuizRevisionDecision;

/**
 * revision을 승인·게시할 수 있는지 Judge 평가로 판정한다.
 *
 * <p>운영 구현은 Judge 연동 전까지 {@link JudgeNotIntegratedQuizApprovalPolicy} 하나뿐이며 항상 거부한다.
 * 설정·Profile로 다른 구현을 고르는 장치를 두지 않는다. Judge 연동 Issue에서 평가 저장소를 검증하는 구현으로
 * 교체한다.
 */
public interface QuizApprovalPolicy {

    /**
     * 승인 직전에 호출한다. 무결성 검증을 마친 revision이 주어진다.
     *
     * @return 승인 기록에 남길 Judge 평가 근거
     * @throws kr.co.cking.common.exception.BusinessException {@code QUIZ_JUDGE_EVALUATION_REQUIRED}
     */
    Basis requireApprovable(Target target);

    /**
     * 게시 직전에 호출한다. 승인 때 기록한 근거가 여전히 같은 revision을 뒷받침하는지 다시 확인한다.
     *
     * @throws kr.co.cking.common.exception.BusinessException {@code QUIZ_JUDGE_EVALUATION_REQUIRED}
     */
    void requireStillApproved(Target target, QuizRevisionDecision approval);

    /** 판정 대상 revision. 해시는 저장 내용으로 다시 계산해 검증한 값이다. */
    record Target(Long quizId, Long revisionId, String contentTextSha256, String revisionSha256) {
    }

    /** 승인 근거가 된 Judge 평가. */
    record Basis(Long judgeEvaluationId, String judgeEvaluationSha256) {
    }
}

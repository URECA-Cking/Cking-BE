package kr.co.cking.quiz.application;

import kr.co.cking.quiz.domain.QuizRevisionDecision;

/**
 * revision을 승인·게시할 수 있는지 Judge 평가로 판정한다.
 *
 * <p>운영 구현은 저장된 불변 Judge 결과를 검증한다. 현재 Provider 설정이나 기능 활성화 여부로 과거 근거를
 * 무효화하지 않는다.
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

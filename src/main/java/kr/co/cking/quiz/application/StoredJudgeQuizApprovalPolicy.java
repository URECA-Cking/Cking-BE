package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.QuizApprovalPolicyType;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.repository.QuizJudgeStoredResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creator 명시적 결정에 필요한 저장 평가 근거를 확인한다. 승인·게시는 이 정책이 실행하지 않는다. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoredJudgeQuizApprovalPolicy implements QuizApprovalPolicy {
    private final QuizJudgeStoredResultRepository resultRepository;
    private final QuizJudgeResultReader resultReader;

    @Override
    public Basis requireApprovable(Target target) {
        QuizJudgeStoredResult result = resultRepository.findByRevisionId(target.revisionId())
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));
        requireValid(target, result);
        return new Basis(result.getResultId(), result.getResultSha256());
    }

    /**
     * {@link QuizApprovalPolicyType#LEGACY_JUDGE_V1} 승인만 검증한다. 다른 정책의 승인, 다른 revision의 승인, 승인 당시
     * 해시와 다른 revision은 무결성 오류다.
     */
    @Override
    public void requireStillApproved(Target target, QuizRevisionDecision approval) {
        if (!approval.isApproved() || !target.revisionId().equals(approval.getRevisionId())
                || approval.getApprovalPolicy() != QuizApprovalPolicyType.LEGACY_JUDGE_V1
                || !target.revisionSha256().equals(approval.getApprovedRevisionSha256())
                || approval.getJudgeEvaluationId() == null) {
            throw invalid();
        }
        QuizJudgeStoredResult result = resultRepository.findById(approval.getJudgeEvaluationId())
                .orElseThrow(this::invalid);
        if (!result.getResultSha256().equals(approval.getJudgeEvaluationSha256())) {
            throw invalid();
        }
        requireValid(target, result);
    }

    private void requireValid(Target target, QuizJudgeStoredResult result) {
        QuizJudgeResultReader.Verified verified = resultReader.read(result);
        if (!target.equals(verified.target())) {
            throw invalid();
        }
        if (verified.evaluation().aggregation() != JudgeAggregation.ALL_PASS) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        }
    }

    private BusinessException invalid() {
        return new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
}

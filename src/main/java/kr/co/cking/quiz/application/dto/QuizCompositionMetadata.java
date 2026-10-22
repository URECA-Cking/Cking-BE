package kr.co.cking.quiz.application.dto;
import kr.co.cking.quiz.domain.*;
import java.util.List;
import java.util.Map;

/** Creator 전용 계보·원본 평가 참고 정보. 공개 응답에는 사용하지 않는다. */
public record QuizCompositionMetadata(QuizRevisionOrigin origin, Long parentRevisionId, String hashVersion,
        String revisionSha256, QuizApprovalPolicyType approvalPolicyVersion, boolean contentLocked, boolean canEdit,
        int questionCount, int optionCount, List<Question> questions) {
    public record Question(Long questionId, int questionNo, Long originQuestionId, Long previousQuestionId,
            QuizQuestionChangeType changeType, String evaluationStatus, SourceEvaluation sourceEvaluation) {}
    public record SourceEvaluation(Long resultId, String resultSha256, Long revisionId, int questionNo, Map<String, Item> items) {}
    public record Item(String verdict, String reason) {}
}

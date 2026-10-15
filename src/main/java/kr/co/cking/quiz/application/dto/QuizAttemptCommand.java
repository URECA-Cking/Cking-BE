package kr.co.cking.quiz.application.dto;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 인증된 Member와 전체 답안을 담는 서버 내부 명령. 표시 순서와 무관하게 정규화한다. */
public record QuizAttemptCommand(Long memberId, Long creatorId, Long quizId, UUID requestId,
        Long revisionId, List<Answer> answers) {
    public QuizAttemptCommand {
        if (memberId == null || memberId <= 0 || creatorId == null || creatorId <= 0
                || quizId == null || quizId <= 0 || revisionId == null || revisionId <= 0 || requestId == null
                || answers == null || answers.isEmpty()
                || answers.stream().anyMatch(a -> a == null || a.questionNo() < 0 || a.selectedOptionIndex() < 0)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        answers = answers.stream().sorted(Comparator.comparingInt(Answer::questionNo)).toList();
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i).questionNo() != i) throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
    public record Answer(int questionNo, int selectedOptionIndex) {}
}

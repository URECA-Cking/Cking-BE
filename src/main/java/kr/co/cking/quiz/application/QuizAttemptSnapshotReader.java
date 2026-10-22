package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import kr.co.cking.quiz.domain.QuizAttempt;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.repository.QuizAttemptAnswerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

/** 저장된 결과 자체를 검증한다. 현재 Quiz/revision은 읽지 않는다. */
@Component @RequiredArgsConstructor
class QuizAttemptSnapshotReader {
    private final QuizAttemptAnswerRepository answers;
    private final ObjectMapper objectMapper;

    QuizAttemptResult read(QuizAttempt a) {
        var rows = answers.findByAttemptIdOrderByQuestionNoAsc(a.getAttemptId());
        try {
            var questions = rows.stream().map(row -> {
                List<String> options = objectMapper.readValue(row.getOptionsJson(), new TypeReference<List<String>>() {});
                if (options.size() != row.getOptionCount()) throw new IllegalStateException("보기 수 불일치");
                return new QuizAttemptResult.Question(row.getQuestionNo(), row.getQuestionText(), options,
                        row.getSelectedOptionIndex(), row.getCorrectOptionIndex(), row.isCorrect(), row.getExplanation(), row.getSourceEvidence());
            }).toList();
            if (questions.isEmpty() || questions.size() != a.getQuestionCount()) throw new IllegalStateException("문항 수 불일치");
            for (int i = 0; i < questions.size(); i++) {
                var q = questions.get(i);
                if (q.questionNo() != i || q.options().size() < 2 || q.selectedOptionIndex() < 0
                        || q.selectedOptionIndex() >= q.options().size() || q.correctOptionIndex() < 0
                        || q.correctOptionIndex() >= q.options().size()
                        || q.correct() != (q.selectedOptionIndex() == q.correctOptionIndex())) throw new IllegalStateException("문항 검증 실패");
            }
            int count = (int) questions.stream().filter(QuizAttemptResult.Question::correct).count();
            if (count != a.getCorrectCount() || a.getRewardAmount() != count
                    || (count > 0) != (a.getRewardRequestId() != null)
                    || !a.getRewardStatus().equals(count > 0 ? "NOT_INTEGRATED" : "NOT_ELIGIBLE")) throw new IllegalStateException("점수/보상 불일치");
            var result = new QuizAttemptResult(a.getAttemptId(), UUID.fromString(a.getRequestId()), a.getCreatorId(),
                    a.getQuizId(), a.getRevisionId(), a.getTitle(), a.getVideoId(), a.getQuestionCount(), count,
                    a.getSubmittedAt(), a.getRevisionSha256(), a.getSnapshotSha256(), questions,
                    new QuizAttemptResult.Reward(a.getRewardAmount(), a.getRewardRequestId() == null ? null : UUID.fromString(a.getRewardRequestId()), a.getRewardStatus()));
            var command = new QuizAttemptCommand(a.getMemberId(), a.getCreatorId(), a.getQuizId(), result.requestId(),
                    a.getRevisionId(), questions.stream().map(q -> new QuizAttemptCommand.Answer(q.questionNo(), q.selectedOptionIndex())).toList());
            if (!QuizAttemptHashes.request(command).equals(a.getRequestFingerprint())
                    || !QuizAttemptHashes.snapshot(a.getMemberId(), a.getRequestFingerprint(), result).equals(a.getSnapshotSha256())) throw new IllegalStateException("스냅샷 hash 불일치");
            return result;
        } catch (RuntimeException e) {
            throw new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        }
    }
}

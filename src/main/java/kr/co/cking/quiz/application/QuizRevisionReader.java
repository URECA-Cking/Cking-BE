package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** revision의 문항·보기를 저장 순서대로 읽고, 승인·게시 전에는 저장 내용을 revision 해시와 대조한다. */
@Slf4j
@Component
@RequiredArgsConstructor
class QuizRevisionReader {

    private final QuizQuestionRepository questionRepository;
    private final QuizOptionRepository optionRepository;

    List<QuizQuestionContent> read(Long revisionId) {
        List<QuizQuestion> questions = questionRepository.findByRevisionIdOrderByQuestionNoAsc(revisionId);
        return toContents(questions, optionsByQuestion(questions));
    }

    /**
     * 원본 텍스트 해시와 저장된 문항으로 revision 해시를 다시 계산해, 생성 시 기록한 값과 다르면
     * {@code QUIZ_REVISION_INTEGRITY_FAILED}로 막는다. 해시에 들어가지 않는 문항·보기 번호와 보기 수도 함께 확인한다.
     */
    List<QuizQuestionContent> readVerified(Quiz quiz, QuizRevision revision) {
        List<QuizQuestion> questionRows = questionRepository.findByRevisionIdOrderByQuestionNoAsc(revision.getRevisionId());
        Map<Long, List<QuizOption>> optionRows = optionsByQuestion(questionRows);
        List<QuizQuestionContent> questions = toContents(questionRows, optionRows);
        boolean intact = quiz.hasIntactContentText()
                && hasExpectedNumbering(revision, questionRows, optionRows)
                && revisionSha256(quiz.getContentTextSha256(), revision.getTitle(), revision.getPromptVersion(),
                        questions).equals(revision.getRevisionSha256());
        if (!intact) {
            log.error("저장된 Quiz 내용이 revision 해시와 다릅니다. quizId={}, revisionId={}",
                    quiz.getQuizId(), revision.getRevisionId());
            throw new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        }
        return questions;
    }

    /** 문항 번호가 0부터 문항 수-1까지, 문항마다 보기 번호가 0부터 보기 수-1까지 빠짐없이 이어지는지 확인한다. */
    private boolean hasExpectedNumbering(QuizRevision revision, List<QuizQuestion> questions,
                                         Map<Long, List<QuizOption>> optionsByQuestion) {
        if (questions.size() != revision.getQuestionCount()) {
            return false;
        }
        for (int questionNo = 0; questionNo < questions.size(); questionNo++) {
            QuizQuestion question = questions.get(questionNo);
            List<QuizOption> options = optionsByQuestion.getOrDefault(question.getQuestionId(), List.of());
            if (question.getQuestionNo() != questionNo || options.size() != revision.getOptionCount()) {
                return false;
            }
            for (int optionNo = 0; optionNo < options.size(); optionNo++) {
                if (options.get(optionNo).getOptionNo() != optionNo) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<Long, List<QuizOption>> optionsByQuestion(List<QuizQuestion> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        return optionRepository
                .findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(
                        questions.stream().map(QuizQuestion::getQuestionId).toList())
                .stream()
                .collect(Collectors.groupingBy(QuizOption::getQuestionId));
    }

    private List<QuizQuestionContent> toContents(List<QuizQuestion> questions,
                                                 Map<Long, List<QuizOption>> optionsByQuestion) {
        return questions.stream()
                .map(question -> new QuizQuestionContent(
                        question.getQuestionNo(),
                        question.getQuestionText(),
                        optionsByQuestion.getOrDefault(question.getQuestionId(), List.of()).stream()
                                .map(QuizOption::getOptionText)
                                .toList(),
                        question.getCorrectOptionIndex(),
                        question.getExplanation(),
                        question.getSourceEvidence()))
                .toList();
    }

    static String revisionSha256(String contentTextSha256, String title, String promptVersion,
                                 List<QuizQuestionContent> questions) {
        return QuizRevisionFingerprint.revisionSha256(contentTextSha256, title, promptVersion,
                questions.stream().map(QuizQuestionContent::toFingerprintQuestion).toList());
    }
}

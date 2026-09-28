package kr.co.cking.quiz.generation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** AI 입력과 Provider 결과의 구조·의미 제약을 검증한다. */
public class QuizGenerationValidator {

    static final int MIN_QUESTION_COUNT = 1;
    static final int MAX_QUESTION_COUNT = 10;
    static final int MIN_OPTION_COUNT = 2;
    static final int MAX_OPTION_COUNT = 6;
    static final int MAX_CONTENT_LENGTH = 50_000;

    public void validateInput(QuizGenerationInput input) {
        if (input == null) {
            throw new QuizInputException("생성 입력이 없습니다.");
        }
        if (input.contentText() == null || input.contentText().isBlank()) {
            throw new QuizInputException("콘텐츠 텍스트는 비어 있을 수 없습니다.");
        }
        if (input.contentText().length() > MAX_CONTENT_LENGTH) {
            throw new QuizInputException("콘텐츠 텍스트가 최대 길이를 초과했습니다.");
        }
        if (input.questionCount() < MIN_QUESTION_COUNT || input.questionCount() > MAX_QUESTION_COUNT) {
            throw new QuizInputException("질문 수는 1~10 사이여야 합니다.");
        }
        if (input.optionCount() < MIN_OPTION_COUNT || input.optionCount() > MAX_OPTION_COUNT) {
            throw new QuizInputException("선택지 수는 2~6 사이여야 합니다.");
        }
    }

    public void validate(QuizGenerationInput input, GeneratedQuiz quiz) {
        validateInput(input);
        if (quiz == null || quiz.questions() == null) {
            throw new QuizGenerationValidationException("생성 결과의 질문 목록이 없습니다.");
        }
        if (quiz.questions().size() != input.questionCount()) {
            throw new QuizGenerationValidationException("요청한 질문 수와 실제 질문 수가 다릅니다.");
        }
        if (quiz.promptVersion() == null || quiz.promptVersion().isBlank()) {
            throw new QuizGenerationValidationException("promptVersion이 없습니다.");
        }

        Set<String> questions = new HashSet<>();
        for (int questionIndex = 0; questionIndex < quiz.questions().size(); questionIndex++) {
            GeneratedQuestion question = quiz.questions().get(questionIndex);
            validateQuestion(input, question, questionIndex);
            String normalizedQuestion = normalize(question.question());
            if (!questions.add(normalizedQuestion)) {
                throw new QuizGenerationValidationException("질문이 중복됩니다: " + questionIndex);
            }
        }
    }

    private void validateQuestion(QuizGenerationInput input, GeneratedQuestion question, int index) {
        if (question == null) {
            throw new QuizGenerationValidationException("질문이 null입니다: " + index);
        }
        if (question.question() == null || question.question().isBlank()) {
            throw new QuizGenerationValidationException("질문이 비어 있습니다: " + index);
        }
        if (question.options() == null || question.options().size() != input.optionCount()) {
            throw new QuizGenerationValidationException("요청한 선택지 수와 실제 선택지 수가 다릅니다: " + index);
        }

        Set<String> options = new HashSet<>();
        for (String option : question.options()) {
            if (option == null || option.isBlank()) {
                throw new QuizGenerationValidationException("선택지가 비어 있습니다: " + index);
            }
            if (!options.add(normalize(option))) {
                throw new QuizGenerationValidationException("선택지가 중복됩니다: " + index);
            }
        }

        if (question.correctOptionIndex() < 0 || question.correctOptionIndex() >= question.options().size()) {
            throw new QuizGenerationValidationException("correctOptionIndex가 유효하지 않습니다: " + index);
        }
        if (question.explanation() == null || question.explanation().isBlank()) {
            throw new QuizGenerationValidationException("해설이 비어 있습니다: " + index);
        }
        if (question.sourceEvidence() == null || question.sourceEvidence().isBlank()) {
            throw new QuizGenerationValidationException("sourceEvidence가 비어 있습니다: " + index);
        }
        if (!input.contentText().contains(question.sourceEvidence())) {
            throw new QuizGenerationValidationException("sourceEvidence가 입력 콘텐츠에 없습니다: " + index);
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

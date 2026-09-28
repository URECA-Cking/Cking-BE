package kr.co.cking.quiz.generation;

import java.util.ArrayList;
import java.util.List;

/**
 * 외부 AI를 호출하지 않는 결정적 테스트 Provider다.
 *
 * <p>Spring Bean으로 등록하지 않으므로 운영 생성 경로를 자동으로 대체하지 않는다.</p>
 */
public class FakeQuizGenerator implements QuizGenerator {

    private final QuizGenerationValidator validator;

    public FakeQuizGenerator() {
        this(new QuizGenerationValidator());
    }

    FakeQuizGenerator(QuizGenerationValidator validator) {
        this.validator = validator;
    }

    @Override
    public GeneratedQuiz generate(QuizGenerationInput input) {
        validator.validateInput(input);
        List<GeneratedQuestion> questions = new ArrayList<>();
        String evidence = input.contentText().trim();
        for (int questionIndex = 1; questionIndex <= input.questionCount(); questionIndex++) {
            List<String> options = new ArrayList<>();
            for (int optionIndex = 1; optionIndex <= input.optionCount(); optionIndex++) {
                options.add("선택지 " + optionIndex);
            }
            questions.add(new GeneratedQuestion(
                    "콘텐츠 확인 질문 " + questionIndex,
                    options,
                    0,
                    "테스트용 결정적 설명입니다.",
                    evidence
            ));
        }
        GeneratedQuiz result = new GeneratedQuiz(questions, QuizPromptVersion.V1.value());
        validator.validate(input, result);
        return result;
    }
}

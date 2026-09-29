package kr.co.cking.quiz.generation.gemini;

import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerationValidator;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizPromptTemplate;
import kr.co.cking.quiz.generation.QuizResponseParser;

/** Prompt 생성부터 Core Parser/Validator까지의 Gemini 퀴즈 생성 흐름을 조정한다. */
public class GeminiQuizGenerator implements QuizGenerator {

    private final GeminiApiClient apiClient;
    private final QuizPromptTemplate promptTemplate;
    private final QuizResponseParser responseParser;
    private final QuizGenerationValidator validator;

    public GeminiQuizGenerator(
            GeminiApiClient apiClient,
            QuizPromptTemplate promptTemplate,
            QuizResponseParser responseParser,
            QuizGenerationValidator validator
    ) {
        this.apiClient = apiClient;
        this.promptTemplate = promptTemplate;
        this.responseParser = responseParser;
        this.validator = validator;
    }

    @Override
    public GeneratedQuiz generate(QuizGenerationInput input) {
        validator.validateInput(input);
        String prompt = promptTemplate.render(input);
        String responseJson = apiClient.generateContent(prompt, input.questionCount(), input.optionCount());
        GeneratedQuiz quiz = responseParser.parse(responseJson);
        validator.validate(input, quiz);
        if (!promptTemplate.version().value().equals(quiz.promptVersion())) {
            throw new QuizGenerationValidationException("생성 결과의 promptVersion이 요청한 프롬프트와 다릅니다.");
        }
        return quiz;
    }
}

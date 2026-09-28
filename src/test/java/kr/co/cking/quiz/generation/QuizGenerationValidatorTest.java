package kr.co.cking.quiz.generation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizGenerationValidatorTest {

    private final QuizGenerationValidator validator = new QuizGenerationValidator();

    @Test
    void 유효한_입력과_생성결과를_통과시킨다() {
        QuizGenerationInput input = new QuizGenerationInput("서울은 대한민국의 수도다.", 1, 4);
        GeneratedQuiz quiz = new GeneratedQuiz(List.of(new GeneratedQuestion(
                "대한민국의 수도는 어디인가요?",
                List.of("서울", "부산", "대전", "광주"),
                0,
                "서울이 대한민국의 수도이기 때문입니다.",
                "서울은 대한민국의 수도다."
        )), QuizPromptVersion.V1.value());

        assertThatCode(() -> validator.validate(input, quiz)).doesNotThrowAnyException();
    }

    @Test
    void 입력_콘텐츠가_공백이면_입력오류다() {
        QuizGenerationInput input = new QuizGenerationInput("   ", 1, 4);

        assertThatThrownBy(() -> validator.validateInput(input))
                .isInstanceOf(QuizInputException.class);
    }

    @Test
    void 질문_수와_선택지_수가_허용범위를_벗어나면_입력오류다() {
        assertThatThrownBy(() -> validator.validateInput(new QuizGenerationInput("콘텐츠", 0, 4)))
                .isInstanceOf(QuizInputException.class);
        assertThatThrownBy(() -> validator.validateInput(new QuizGenerationInput("콘텐츠", 1, 1)))
                .isInstanceOf(QuizInputException.class);
    }

    @Test
    void 질문_수와_생성결과_수가_다르면_검증오류다() {
        QuizGenerationInput input = new QuizGenerationInput("콘텐츠", 2, 4);
        GeneratedQuiz quiz = new GeneratedQuiz(List.of(questionWith("콘텐츠")), QuizPromptVersion.V1.value());

        assertThatThrownBy(() -> validator.validate(input, quiz))
                .isInstanceOf(QuizGenerationValidationException.class)
                .hasMessageContaining("질문 수");
    }

    @Test
    void 중복_선택지는_검증오류다() {
        QuizGenerationInput input = new QuizGenerationInput("콘텐츠", 1, 4);
        GeneratedQuiz quiz = new GeneratedQuiz(List.of(new GeneratedQuestion(
                "질문", List.of("같음", "같음", "다름", "또 다름"), 0,
                "설명", "콘텐츠")), QuizPromptVersion.V1.value());

        assertThatThrownBy(() -> validator.validate(input, quiz))
                .isInstanceOf(QuizGenerationValidationException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void 정답_인덱스가_범위를_벗어나면_검증오류다() {
        QuizGenerationInput input = new QuizGenerationInput("콘텐츠", 1, 4);
        GeneratedQuiz quiz = new GeneratedQuiz(List.of(new GeneratedQuestion(
                "질문", List.of("하나", "둘", "셋", "넷"), 4,
                "설명", "콘텐츠")), QuizPromptVersion.V1.value());

        assertThatThrownBy(() -> validator.validate(input, quiz))
                .isInstanceOf(QuizGenerationValidationException.class)
                .hasMessageContaining("correctOptionIndex");
    }

    @Test
    void 근거가_입력_콘텐츠에_없으면_검증오류다() {
        QuizGenerationInput input = new QuizGenerationInput("원문 콘텐츠", 1, 4);
        GeneratedQuiz quiz = new GeneratedQuiz(List.of(questionWith("없는 근거")), QuizPromptVersion.V1.value());

        assertThatThrownBy(() -> validator.validate(input, quiz))
                .isInstanceOf(QuizGenerationValidationException.class)
                .hasMessageContaining("sourceEvidence");
    }

    private GeneratedQuestion questionWith(String evidence) {
        return new GeneratedQuestion(
                "질문", List.of("하나", "둘", "셋", "넷"), 0, "설명", evidence);
    }
}
